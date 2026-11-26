package com.pillscope.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.pillscope.app.data.local.PillScopeDatabase
import com.pillscope.app.data.local.entity.CachedDoseScheduleEntity
import com.pillscope.app.data.local.entity.CachedLifestyleTriggerEntity
import com.pillscope.app.data.local.entity.CachedRecallAlertEntity
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.local.entity.ProfileEntity
import com.pillscope.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomMigrationTest {

    @Test
    fun migration1To2PreservesExistingRowsAndInitializesFlagshipTables() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "pillscope-migration-test-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS cabinet_medications (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    brand_mapping_id TEXT,
                                    display_name TEXT NOT NULL,
                                    generic_salts_json TEXT NOT NULL,
                                    canonical_salt_keys_csv TEXT NOT NULL,
                                    dosage_schedule TEXT,
                                    is_active INTEGER NOT NULL,
                                    sync_status TEXT NOT NULL,
                                    updated_at_epoch_ms INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE IF NOT EXISTS cached_scan_reports (
                                    scan_id TEXT NOT NULL PRIMARY KEY,
                                    submitted_query TEXT NOT NULL,
                                    resolved_salts_json TEXT NOT NULL,
                                    overall_risk TEXT NOT NULL,
                                    safety_banner_locked INTEGER NOT NULL,
                                    duplicate_salts_json TEXT NOT NULL,
                                    deterministic_alerts_json TEXT NOT NULL,
                                    interactions_json TEXT NOT NULL,
                                    clinical_summary_json TEXT NOT NULL,
                                    citations_json TEXT NOT NULL,
                                    citation_verified INTEGER NOT NULL,
                                    latency_ms INTEGER NOT NULL,
                                    created_at_epoch_ms INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO cabinet_medications (
                                    id, brand_mapping_id, display_name, generic_salts_json,
                                    canonical_salt_keys_csv, dosage_schedule, is_active,
                                    sync_status, updated_at_epoch_ms
                                ) VALUES (
                                    'med-v1-1', NULL, 'Warf 5 Tablet',
                                    '[{"canonical_salt":"warfarin","strength_mg":5.0}]',
                                    'warfarin', '1 tab OD', 1, 'SYNCED', 1700000000000
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                INSERT INTO cached_scan_reports (
                                    scan_id, submitted_query, resolved_salts_json, overall_risk,
                                    safety_banner_locked, duplicate_salts_json,
                                    deterministic_alerts_json, interactions_json,
                                    clinical_summary_json, citations_json, citation_verified,
                                    latency_ms, created_at_epoch_ms
                                ) VALUES (
                                    'scan-v1-1', 'Ecosprin 75', '["aspirin"]', 'MAJOR',
                                    1, '[]', '[]', '[]', '{}', '[]', 1, 110, 1700000001000
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        v1Helper.writableDatabase.close()
        v1Helper.close()

        val migratedDb = Room.databaseBuilder(context, PillScopeDatabase::class.java, dbName)
            .addMigrations(PillScopeDatabase.MIGRATION_1_2, PillScopeDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val defaultProfile = migratedDb.profileDao().getDefaultProfile()
            assertNotNull(defaultProfile)
            assertEquals(DefaultProfileIds.LOCAL_DEFAULT, defaultProfile!!.id)
            assertTrue(defaultProfile.isDefault)
            assertEquals(SyncStatus.SYNCED, defaultProfile.syncStatus)

            val preservedMed = migratedDb.medicationDao().getById("med-v1-1")
            assertNotNull(preservedMed)
            assertEquals("Warf 5 Tablet", preservedMed!!.displayName)
            assertEquals(DefaultProfileIds.LOCAL_DEFAULT, preservedMed.profileId)
            assertEquals("[]", preservedMed.lifestyleTriggersJson)
            assertFalse(preservedMed.hasActiveRecall)
            assertEquals("[]", preservedMed.activeRecallsJson)

            val preservedScan = migratedDb.scanReportDao().getByScanId("scan-v1-1")
            assertNotNull(preservedScan)
            assertEquals(DefaultProfileIds.LOCAL_DEFAULT, preservedScan!!.profileId)
            assertTrue(preservedScan.persisted)
            assertEquals("[]", preservedScan.allergyAlertsJson)
            assertEquals("[]", preservedScan.lifestyleTriggersJson)
            assertEquals("[]", preservedScan.activeRecallsJson)

            migratedDb.profileDao().upsert(
                ProfileEntity(
                    id = "prof-mom",
                    userId = "u-1",
                    name = "Mom",
                    relationship = "mother",
                    allergiesJson = """["penicillin","sulfa"]""",
                    isDefault = false,
                    updatedAtEpochMs = 1700000005000L,
                ),
            )
            assertEquals(2, migratedDb.profileDao().getAllProfiles().size)

            migratedDb.lifestyleTriggerDao().upsertAll(
                listOf(
                    CachedLifestyleTriggerEntity(
                        id = "warfarin:ALCOHOL",
                        canonicalSalt = "warfarin",
                        triggerType = "ALCOHOL",
                        severity = "HIGH",
                        shortNote = "Alcohol alters INR and raises bleeding risk.",
                        sourceSection = "warnings",
                        updatedAtEpochMs = 1700000005000L,
                    ),
                ),
            )
            val triggers = migratedDb.lifestyleTriggerDao().getForSalts(listOf("warfarin"))
            assertEquals(1, triggers.size)
            assertEquals("ALCOHOL", triggers.first().triggerType)

            migratedDb.doseScheduleDao().upsert(
                CachedDoseScheduleEntity(
                    profileId = "prof-mom",
                    profileName = "Mom",
                    safetyBannerLocked = false,
                    scheduleJson = """{"profile_id":"prof-mom","profile_name":"Mom"}""",
                    updatedAtEpochMs = 1700000005000L,
                ),
            )
            val cachedSchedule = migratedDb.doseScheduleDao().getForProfile("prof-mom")
            assertNotNull(cachedSchedule)
            assertEquals("Mom", cachedSchedule!!.profileName)

            migratedDb.recallAlertDao().upsertAll(
                listOf(
                    CachedRecallAlertEntity(
                        id = "prof-mom:rec-1:med-v1-1",
                        profileId = "prof-mom",
                        recallId = "rec-1",
                        recallNumber = "D-0123-2026",
                        alertLabel = "US FDA Recall Alert",
                        classification = "Class I",
                        status = "Ongoing",
                        recallingFirm = "Example Pharma",
                        productDescription = "Warfarin Sodium 5mg Tablets",
                        reasonForRecall = "Superpotent tablets detected in batch.",
                        reportDate = "2026-05-01",
                        sourceUrl = "https://api.fda.gov/drug/enforcement.json?search=recall_number:D-0123-2026",
                        matchedMedicationId = "med-v1-1",
                        matchedMedicationName = "Warf 5 Tablet",
                        matchBasis = "generic_salt",
                        matchedTermsJson = """["warfarin"]""",
                        updatedAtEpochMs = 1700000005000L,
                    ),
                ),
            )
            val recalls = migratedDb.recallAlertDao().observeForProfile("prof-mom").first()
            assertEquals(1, recalls.size)
            assertEquals("Class I", recalls.first().classification)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
