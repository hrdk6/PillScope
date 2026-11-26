package com.pillscope.app

import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.MedicationUpdateRequestDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import com.pillscope.app.data.remote.sse.ScanSseParser
import com.pillscope.app.data.repository.CabinetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class InMemoryMedicationDao : MedicationDao {
    private val store = MutableStateFlow<Map<String, MedicationEntity>>(emptyMap())

    override fun observeActiveMedications(): Flow<List<MedicationEntity>> =
        store.map { map ->
            map.values
                .filter { it.isActive && it.syncStatus != SyncStatus.PENDING_DELETE }
                .sortedByDescending { it.updatedAtEpochMs }
        }

    override fun observeActiveMedicationsForProfile(profileId: String): Flow<List<MedicationEntity>> =
        store.map { map ->
            map.values
                .filter {
                    it.profileId == profileId &&
                        it.isActive &&
                        it.syncStatus != SyncStatus.PENDING_DELETE
                }
                .sortedByDescending { it.updatedAtEpochMs }
        }

    override fun observeAllVisibleMedications(): Flow<List<MedicationEntity>> =
        store.map { map ->
            map.values
                .filter { it.syncStatus != SyncStatus.PENDING_DELETE }
                .sortedWith(compareByDescending<MedicationEntity> { it.isActive }.thenByDescending { it.updatedAtEpochMs })
        }

    override fun observeVisibleMedicationsForProfile(profileId: String): Flow<List<MedicationEntity>> =
        store.map { map ->
            map.values
                .filter { it.profileId == profileId && it.syncStatus != SyncStatus.PENDING_DELETE }
                .sortedWith(compareByDescending<MedicationEntity> { it.isActive }.thenByDescending { it.updatedAtEpochMs })
        }

    override suspend fun getById(id: String): MedicationEntity? = store.value[id]

    override suspend fun getPendingSync(): List<MedicationEntity> =
        store.value.values.filter { it.syncStatus != SyncStatus.SYNCED }

    override suspend fun getActiveSnapshot(): List<MedicationEntity> =
        store.value.values.filter { it.isActive && it.syncStatus != SyncStatus.PENDING_DELETE }

    override suspend fun getActiveSnapshotForProfile(profileId: String): List<MedicationEntity> =
        store.value.values.filter {
            it.profileId == profileId &&
                it.isActive &&
                it.syncStatus != SyncStatus.PENDING_DELETE
        }

    override suspend fun upsert(entity: MedicationEntity) {
        store.value = store.value + (entity.id to entity)
    }

    override suspend fun upsertAll(entities: List<MedicationEntity>) {
        val mutable = store.value.toMutableMap()
        entities.forEach { mutable[it.id] = it }
        store.value = mutable
    }

    override suspend fun deleteById(id: String) {
        store.value = store.value - id
    }

    override suspend fun clearSynced() {
        store.value = store.value.filterValues { it.syncStatus != SyncStatus.SYNCED }
    }

    override suspend fun clearSyncedForProfile(profileId: String) {
        store.value = store.value.filterValues {
            !(it.profileId == profileId && it.syncStatus == SyncStatus.SYNCED)
        }
    }

    override suspend fun reassignProfileId(fromProfileId: String, toProfileId: String) {
        store.value = store.value.mapValues { (_, entity) ->
            if (entity.profileId == fromProfileId) entity.copy(profileId = toProfileId) else entity
        }
    }
}

class CabinetRepositoryTest {
    private fun createApi(baseUrl: String): PillScopeApi {
        val json = ScanSseParser.defaultSseJson
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PillScopeApi::class.java)
    }

    @Test
    fun offlineAddQueuesLocallyDetectsDuplicatesAndSyncsWhenOnline() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val dao = InMemoryMedicationDao()
            val repo = CabinetRepository(
                medicationDao = dao,
                api = createApi(server.url("/").toString()),
                json = ScanSseParser.defaultSseJson,
            )

            server.enqueue(MockResponse().setResponseCode(503))
            val firstAdd = repo.addMedicationOfflineFirst(
                displayName = "Mox 500mg Capsule",
                genericSalts = listOf(SaltComponentDto(canonicalSalt = "amoxicillin", strengthMg = 500.0)),
                dosageSchedule = "1 cap TID",
            )
            assertFalse(firstAdd.syncedWithServer)
            assertTrue(firstAdd.localDuplicateAlerts.isEmpty())
            assertEquals(SyncStatus.PENDING_CREATE, firstAdd.medication.syncStatus)

            val dupAlerts = repo.checkOfflineDuplicates(listOf("amoxicillin", "clavulanic acid"))
            assertEquals(1, dupAlerts.size)
            assertEquals("amoxicillin", dupAlerts[0].canonicalSalt)
            assertEquals("Mox 500mg Capsule", dupAlerts[0].existingDisplayName)

            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setBody(
                        """
                        {
                          "medication": {
                            "id": "srv-med-101",
                            "user_id": "u-1",
                            "display_name": "Mox 500mg Capsule",
                            "generic_salts": [{"canonical_salt": "amoxicillin", "strength_mg": 500.0}],
                            "canonical_salt_keys": ["amoxicillin"],
                            "dosage_schedule": "1 cap TID",
                            "is_active": true
                          },
                          "overall_risk": "NONE",
                          "safety_banner_locked": false,
                          "duplicate_salts": []
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        [
                          {
                            "id": "srv-med-101",
                            "user_id": "u-1",
                            "display_name": "Mox 500mg Capsule",
                            "generic_salts": [{"canonical_salt": "amoxicillin", "strength_mg": 500.0}],
                            "canonical_salt_keys": ["amoxicillin"],
                            "dosage_schedule": "1 cap TID",
                            "is_active": true
                          },
                          {
                            "id": "srv-med-202",
                            "user_id": "u-1",
                            "display_name": "Warf 5 Tablet",
                            "generic_salts": [{"canonical_salt": "warfarin", "strength_mg": 5.0}],
                            "canonical_salt_keys": ["warfarin"],
                            "dosage_schedule": "1 tab OD",
                            "is_active": true
                          }
                        ]
                        """.trimIndent(),
                    ),
            )

            val syncReport = repo.syncWithBackend()
            assertTrue(syncReport.isSuccess)
            assertEquals(1, syncReport.pushedCreates)
            assertEquals(2, syncReport.pulledCount)

            val active = repo.observeActiveMedications().first()
            assertEquals(2, active.size)
            assertTrue(active.all { !it.isPendingSync })
            assertEquals(setOf("srv-med-101", "srv-med-202"), active.map { it.id }.toSet())

            server.enqueue(MockResponse().setResponseCode(204))
            val deleted = repo.deleteMedication("srv-med-101")
            assertTrue(deleted)
            val remaining = repo.observeActiveMedications().first()
            assertEquals(1, remaining.size)
            assertEquals("srv-med-202", remaining.first().id)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun partialMedicationUpdateOmitsNullFieldsToPreventBackendOverwrite() {
        val body = ScanSseParser.defaultSseJson.encodeToString(
            MedicationUpdateRequestDto(isActive = false),
        )
        assertEquals("""{"is_active":false}""", body)
        assertFalse(body.contains("display_name"))
        assertFalse(body.contains("dosage_schedule"))
    }
}
