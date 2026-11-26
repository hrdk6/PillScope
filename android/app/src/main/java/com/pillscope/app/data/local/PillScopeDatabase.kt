package com.pillscope.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pillscope.app.data.local.dao.DoseScheduleDao
import com.pillscope.app.data.local.dao.LifestyleTriggerDao
import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.dao.ProfileDao
import com.pillscope.app.data.local.dao.RecallAlertDao
import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.CachedDoseScheduleEntity
import com.pillscope.app.data.local.entity.CachedLifestyleTriggerEntity
import com.pillscope.app.data.local.entity.CachedRecallAlertEntity
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.ProfileEntity

@Database(
    entities = [
        ProfileEntity::class,
        MedicationEntity::class,
        CachedScanReportEntity::class,
        CachedLifestyleTriggerEntity::class,
        CachedDoseScheduleEntity::class,
        CachedRecallAlertEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class PillScopeDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun medicationDao(): MedicationDao
    abstract fun scanReportDao(): ScanReportDao
    abstract fun lifestyleTriggerDao(): LifestyleTriggerDao
    abstract fun doseScheduleDao(): DoseScheduleDao
    abstract fun recallAlertDao(): RecallAlertDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS profiles (
                        id TEXT NOT NULL PRIMARY KEY,
                        user_id TEXT NOT NULL DEFAULT '',
                        name TEXT NOT NULL,
                        relationship TEXT NOT NULL DEFAULT 'self',
                        allergies_json TEXT NOT NULL DEFAULT '[]',
                        notes TEXT,
                        is_default INTEGER NOT NULL DEFAULT 0,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO profiles (
                        id, user_id, name, relationship, allergies_json, notes, is_default, updated_at_epoch_ms
                    ) VALUES (
                        '${DefaultProfileIds.LOCAL_DEFAULT}', '', 'Primary Profile', 'self', '[]', NULL, 1, 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "ALTER TABLE cabinet_medications ADD COLUMN profile_id TEXT NOT NULL DEFAULT '${DefaultProfileIds.LOCAL_DEFAULT}'",
                )
                db.execSQL(
                    "ALTER TABLE cabinet_medications ADD COLUMN lifestyle_triggers_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE cabinet_medications ADD COLUMN has_active_recall INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE cabinet_medications ADD COLUMN active_recalls_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE cached_scan_reports ADD COLUMN profile_id TEXT NOT NULL DEFAULT '${DefaultProfileIds.LOCAL_DEFAULT}'",
                )
                db.execSQL(
                    "ALTER TABLE cached_scan_reports ADD COLUMN persisted INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE cached_scan_reports ADD COLUMN allergy_alerts_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE cached_scan_reports ADD COLUMN lifestyle_triggers_json TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lifestyle_triggers (
                        id TEXT NOT NULL PRIMARY KEY,
                        canonical_salt TEXT NOT NULL,
                        trigger_type TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        short_note TEXT NOT NULL,
                        source_section TEXT NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dose_schedule (
                        profile_id TEXT NOT NULL PRIMARY KEY,
                        profile_name TEXT NOT NULL,
                        safety_banner_locked INTEGER NOT NULL,
                        schedule_json TEXT NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recall_alerts (
                        id TEXT NOT NULL PRIMARY KEY,
                        profile_id TEXT NOT NULL,
                        recall_id TEXT NOT NULL,
                        recall_number TEXT NOT NULL,
                        alert_label TEXT NOT NULL,
                        classification TEXT NOT NULL,
                        status TEXT NOT NULL,
                        recalling_firm TEXT,
                        product_description TEXT NOT NULL,
                        reason_for_recall TEXT NOT NULL,
                        report_date TEXT,
                        source_url TEXT NOT NULL,
                        matched_medication_id TEXT NOT NULL,
                        matched_medication_name TEXT NOT NULL,
                        match_basis TEXT NOT NULL,
                        matched_terms_json TEXT NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
