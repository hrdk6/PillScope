package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.MedicationCreateRequestDto
import com.pillscope.app.data.remote.dto.MedicationResponseDto
import com.pillscope.app.data.remote.dto.MedicationUpdateRequestDto
import com.pillscope.app.data.remote.dto.SaltComponentDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class CabinetMedication(
    val id: String,
    val brandMappingId: String?,
    val displayName: String,
    val genericSalts: List<SaltComponentDto>,
    val canonicalSaltKeys: List<String>,
    val dosageSchedule: String?,
    val isActive: Boolean,
    val syncStatus: String,
    val updatedAtEpochMs: Long,
) {
    val isPendingSync: Boolean get() = syncStatus != SyncStatus.SYNCED
}

data class OfflineAddResult(
    val medication: CabinetMedication,
    val localDuplicateAlerts: List<DuplicateSaltAlertDto>,
    val syncedWithServer: Boolean,
)

data class CabinetSyncReport(
    val pushedCreates: Int = 0,
    val pushedUpdates: Int = 0,
    val pushedDeletes: Int = 0,
    val pulledCount: Int = 0,
    val failedOperations: Int = 0,
) {
    val isSuccess: Boolean get() = failedOperations == 0
}

@Singleton
class CabinetRepository @Inject constructor(
    private val medicationDao: MedicationDao,
    private val api: PillScopeApi,
    private val json: Json,
) {
    fun observeActiveMedications(): Flow<List<CabinetMedication>> =
        medicationDao.observeActiveMedications().map { list -> list.map(::toDomain) }

    fun observeAllVisibleMedications(): Flow<List<CabinetMedication>> =
        medicationDao.observeAllVisibleMedications().map { list -> list.map(::toDomain) }

    suspend fun checkOfflineDuplicates(candidateSalts: List<String>): List<DuplicateSaltAlertDto> {
        val candidateSet = candidateSalts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (candidateSet.isEmpty()) return emptyList()

        val activeMeds = medicationDao.getActiveSnapshot().map(::toDomain)
        val alerts = mutableListOf<DuplicateSaltAlertDto>()
        for (med in activeMeds) {
            val overlap = med.canonicalSaltKeys
                .map { it.trim().lowercase() }
                .filter { it in candidateSet }
                .sorted()
            for (salt in overlap) {
                alerts.add(
                    DuplicateSaltAlertDto(
                        canonicalSalt = salt,
                        existingMedicationId = med.id,
                        existingDisplayName = med.displayName,
                    ),
                )
            }
        }
        return alerts.sortedWith(compareBy({ it.canonicalSalt }, { it.existingDisplayName }))
    }

    suspend fun addMedicationOfflineFirst(
        displayName: String,
        genericSalts: List<SaltComponentDto>,
        brandMappingId: String? = null,
        dosageSchedule: String? = null,
    ): OfflineAddResult {
        val normalizedKeys = genericSalts
            .map { it.canonicalSalt.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val localDuplicates = checkOfflineDuplicates(normalizedKeys)
        val localId = "local-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val pendingEntity = MedicationEntity(
            id = localId,
            brandMappingId = brandMappingId,
            displayName = displayName.trim(),
            genericSaltsJson = json.encodeToString(genericSalts),
            canonicalSaltKeysCsv = normalizedKeys.joinToString(","),
            dosageSchedule = dosageSchedule?.trim()?.takeIf { it.isNotEmpty() },
            isActive = true,
            syncStatus = SyncStatus.PENDING_CREATE,
            updatedAtEpochMs = now,
        )
        medicationDao.upsert(pendingEntity)

        val remoteResult = runCatching {
            api.createMedication(
                MedicationCreateRequestDto(
                    brandMappingId = brandMappingId,
                    displayName = displayName.trim(),
                    genericSalts = genericSalts,
                    dosageSchedule = dosageSchedule?.trim()?.takeIf { it.isNotEmpty() },
                    isActive = true,
                ),
            )
        }.getOrNull()

        return if (remoteResult != null) {
            val syncedEntity = fromRemoteDto(remoteResult.medication, now)
            medicationDao.deleteById(localId)
            medicationDao.upsert(syncedEntity)
            OfflineAddResult(
                medication = toDomain(syncedEntity),
                localDuplicateAlerts = remoteResult.duplicateSalts.ifEmpty { localDuplicates },
                syncedWithServer = true,
            )
        } else {
            OfflineAddResult(
                medication = toDomain(pendingEntity),
                localDuplicateAlerts = localDuplicates,
                syncedWithServer = false,
            )
        }
    }

    suspend fun toggleMedicationActive(id: String, isActive: Boolean): Boolean {
        val existing = medicationDao.getById(id) ?: return false
        val now = System.currentTimeMillis()
        val nextSyncStatus = if (existing.syncStatus == SyncStatus.PENDING_CREATE) {
            SyncStatus.PENDING_CREATE
        } else {
            SyncStatus.PENDING_UPDATE
        }
        val updatedLocal = existing.copy(
            isActive = isActive,
            syncStatus = nextSyncStatus,
            updatedAtEpochMs = now,
        )
        medicationDao.upsert(updatedLocal)

        if (nextSyncStatus == SyncStatus.PENDING_CREATE) {
            return false
        }

        val remote = runCatching {
            api.updateMedication(id, MedicationUpdateRequestDto(isActive = isActive))
        }.getOrNull()

        return if (remote != null) {
            medicationDao.upsert(fromRemoteDto(remote, now))
            true
        } else {
            false
        }
    }

    suspend fun deleteMedication(id: String): Boolean {
        val existing = medicationDao.getById(id) ?: return false
        if (existing.syncStatus == SyncStatus.PENDING_CREATE) {
            medicationDao.deleteById(id)
            return true
        }

        val now = System.currentTimeMillis()
        medicationDao.upsert(
            existing.copy(
                syncStatus = SyncStatus.PENDING_DELETE,
                updatedAtEpochMs = now,
            ),
        )

        val remoteDeleted = runCatching {
            val response = api.deleteMedication(id)
            response.isSuccessful || response.code() == 404
        }.getOrDefault(false)

        if (remoteDeleted) {
            medicationDao.deleteById(id)
        }
        return remoteDeleted
    }

    suspend fun syncWithBackend(): CabinetSyncReport {
        var creates = 0
        var updates = 0
        var deletes = 0
        var failed = 0
        val now = System.currentTimeMillis()

        val pending = medicationDao.getPendingSync()
        for (entity in pending) {
            when (entity.syncStatus) {
                SyncStatus.PENDING_CREATE -> {
                    val salts = decodeSalts(entity.genericSaltsJson)
                    val remote = runCatching {
                        api.createMedication(
                            MedicationCreateRequestDto(
                                brandMappingId = entity.brandMappingId,
                                displayName = entity.displayName,
                                genericSalts = salts,
                                dosageSchedule = entity.dosageSchedule,
                                isActive = entity.isActive,
                            ),
                        )
                    }.getOrNull()

                    if (remote != null) {
                        medicationDao.deleteById(entity.id)
                        medicationDao.upsert(fromRemoteDto(remote.medication, now))
                        creates++
                    } else {
                        failed++
                    }
                }

                SyncStatus.PENDING_UPDATE -> {
                    val remote = runCatching {
                        api.updateMedication(
                            entity.id,
                            MedicationUpdateRequestDto(
                                displayName = entity.displayName,
                                dosageSchedule = entity.dosageSchedule,
                                isActive = entity.isActive,
                            ),
                        )
                    }.getOrNull()

                    if (remote != null) {
                        medicationDao.upsert(fromRemoteDto(remote, now))
                        updates++
                    } else {
                        failed++
                    }
                }

                SyncStatus.PENDING_DELETE -> {
                    val deleted = runCatching {
                        val response = api.deleteMedication(entity.id)
                        response.isSuccessful || response.code() == 404
                    }.getOrDefault(false)

                    if (deleted) {
                        medicationDao.deleteById(entity.id)
                        deletes++
                    } else {
                        failed++
                    }
                }
            }
        }

        val remoteList = runCatching { api.listMedications(activeOnly = false) }.getOrNull()
        val pulledCount = if (remoteList != null) {
            val stillPendingIds = medicationDao.getPendingSync().map { it.id }.toSet()
            medicationDao.clearSynced()
            val remoteEntities = remoteList
                .filter { it.id !in stillPendingIds }
                .map { fromRemoteDto(it, now) }
            medicationDao.upsertAll(remoteEntities)
            remoteEntities.size
        } else {
            failed++
            0
        }

        return CabinetSyncReport(
            pushedCreates = creates,
            pushedUpdates = updates,
            pushedDeletes = deletes,
            pulledCount = pulledCount,
            failedOperations = failed,
        )
    }

    private fun fromRemoteDto(dto: MedicationResponseDto, timestampMs: Long): MedicationEntity =
        MedicationEntity(
            id = dto.id,
            brandMappingId = dto.brandMappingId,
            displayName = dto.displayName,
            genericSaltsJson = json.encodeToString(dto.genericSalts),
            canonicalSaltKeysCsv = dto.canonicalSaltKeys.joinToString(","),
            dosageSchedule = dto.dosageSchedule,
            isActive = dto.isActive,
            syncStatus = SyncStatus.SYNCED,
            updatedAtEpochMs = timestampMs,
        )

    private fun decodeSalts(rawJson: String): List<SaltComponentDto> =
        runCatching { json.decodeFromString<List<SaltComponentDto>>(rawJson) }.getOrDefault(emptyList())

    private fun toDomain(entity: MedicationEntity): CabinetMedication = CabinetMedication(
        id = entity.id,
        brandMappingId = entity.brandMappingId,
        displayName = entity.displayName,
        genericSalts = decodeSalts(entity.genericSaltsJson),
        canonicalSaltKeys = entity.canonicalSaltKeysCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        dosageSchedule = entity.dosageSchedule,
        isActive = entity.isActive,
        syncStatus = entity.syncStatus,
        updatedAtEpochMs = entity.updatedAtEpochMs,
    )
}
