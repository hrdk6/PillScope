package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.LifestyleTriggerDao
import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.entity.CachedLifestyleTriggerEntity
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyAlertDto
import com.pillscope.app.data.remote.dto.DuplicateSaltAlertDto
import com.pillscope.app.data.remote.dto.LifestyleTriggerDto
import com.pillscope.app.data.remote.dto.MedicationCreateRequestDto
import com.pillscope.app.data.remote.dto.MedicationRecallFlagDto
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
    val profileId: String = DefaultProfileIds.LOCAL_DEFAULT,
    val brandMappingId: String?,
    val displayName: String,
    val genericSalts: List<SaltComponentDto>,
    val canonicalSaltKeys: List<String>,
    val dosageSchedule: String?,
    val isActive: Boolean,
    val syncStatus: String,
    val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
    val hasActiveRecall: Boolean = false,
    val activeRecalls: List<MedicationRecallFlagDto> = emptyList(),
    val updatedAtEpochMs: Long,
) {
    val isPendingSync: Boolean get() = syncStatus != SyncStatus.SYNCED
}

data class OfflineAddResult(
    val medication: CabinetMedication,
    val localDuplicateAlerts: List<DuplicateSaltAlertDto>,
    val allergyAlerts: List<AllergyAlertDto> = emptyList(),
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
class CabinetRepository {
    private val medicationDao: MedicationDao
    private val api: PillScopeApi
    private val json: Json
    private val lifestyleTriggerDao: LifestyleTriggerDao?

    @Inject
    constructor(
        medicationDao: MedicationDao,
        api: PillScopeApi,
        json: Json,
        lifestyleTriggerDao: LifestyleTriggerDao,
    ) {
        this.medicationDao = medicationDao
        this.api = api
        this.json = json
        this.lifestyleTriggerDao = lifestyleTriggerDao
    }

    constructor(
        medicationDao: MedicationDao,
        api: PillScopeApi,
        json: Json,
    ) {
        this.medicationDao = medicationDao
        this.api = api
        this.json = json
        this.lifestyleTriggerDao = null
    }

    fun observeActiveMedications(profileId: String? = null): Flow<List<CabinetMedication>> =
        if (profileId.isNullOrBlank()) {
            medicationDao.observeActiveMedications().map { list -> list.map(::toDomain) }
        } else {
            medicationDao.observeActiveMedicationsForProfile(profileId).map { list ->
                list.map(::toDomain)
            }
        }

    fun observeAllVisibleMedications(profileId: String? = null): Flow<List<CabinetMedication>> =
        if (profileId.isNullOrBlank()) {
            medicationDao.observeAllVisibleMedications().map { list -> list.map(::toDomain) }
        } else {
            medicationDao.observeVisibleMedicationsForProfile(profileId).map { list ->
                list.map(::toDomain)
            }
        }

    suspend fun checkOfflineDuplicates(
        candidateSalts: List<String>,
        profileId: String? = null,
    ): List<DuplicateSaltAlertDto> {
        val candidateSet = candidateSalts
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (candidateSet.isEmpty()) return emptyList()

        val activeMeds = if (profileId.isNullOrBlank()) {
            medicationDao.getActiveSnapshot()
        } else {
            medicationDao.getActiveSnapshotForProfile(profileId)
        }.map(::toDomain)

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
        profileId: String? = null,
    ): OfflineAddResult {
        val resolvedProfileId = profileId?.takeIf { it.isNotBlank() } ?: DefaultProfileIds.LOCAL_DEFAULT
        val serverProfileId = profileId?.takeIf { it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT }
        val normalizedKeys = genericSalts
            .map { it.canonicalSalt.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        val localDuplicates = checkOfflineDuplicates(normalizedKeys, resolvedProfileId)
        val localId = "local-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val pendingEntity = MedicationEntity(
            id = localId,
            profileId = resolvedProfileId,
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
                    profileId = serverProfileId,
                    brandMappingId = brandMappingId,
                    displayName = displayName.trim(),
                    genericSalts = genericSalts,
                    dosageSchedule = dosageSchedule?.trim()?.takeIf { it.isNotEmpty() },
                    isActive = true,
                ),
            )
        }.getOrNull()

        return if (remoteResult != null) {
            val syncedEntity = fromRemoteDto(remoteResult.medication, resolvedProfileId, now)
            medicationDao.deleteById(localId)
            medicationDao.upsert(syncedEntity)
            cacheLifestyleTriggers(remoteResult.medication, now)
            OfflineAddResult(
                medication = toDomain(syncedEntity),
                localDuplicateAlerts = remoteResult.duplicateSalts.ifEmpty { localDuplicates },
                allergyAlerts = remoteResult.allergyAlerts,
                syncedWithServer = true,
            )
        } else {
            OfflineAddResult(
                medication = toDomain(pendingEntity),
                localDuplicateAlerts = localDuplicates,
                allergyAlerts = emptyList(),
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
            medicationDao.upsert(fromRemoteDto(remote, existing.profileId, now))
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

    suspend fun syncWithBackend(profileId: String? = null): CabinetSyncReport {
        var creates = 0
        var updates = 0
        var deletes = 0
        var failed = 0
        val now = System.currentTimeMillis()
        val serverProfileId = profileId?.takeIf {
            it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
        }

        val pending = medicationDao.getPendingSync()
        for (entity in pending) {
            when (entity.syncStatus) {
                SyncStatus.PENDING_CREATE -> {
                    val salts = decodeSalts(entity.genericSaltsJson)
                    val targetProfile = entity.profileId.takeIf {
                        it.isNotBlank() && !it.startsWith("local-") && it != DefaultProfileIds.LOCAL_DEFAULT
                    } ?: serverProfileId
                    val remote = runCatching {
                        api.createMedication(
                            MedicationCreateRequestDto(
                                profileId = targetProfile,
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
                        medicationDao.upsert(fromRemoteDto(remote.medication, entity.profileId, now))
                        cacheLifestyleTriggers(remote.medication, now)
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
                        medicationDao.upsert(fromRemoteDto(remote, entity.profileId, now))
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

        val remoteList = runCatching {
            api.listMedications(profileId = serverProfileId, activeOnly = false)
        }.getOrNull()

        val pulledCount = if (remoteList != null) {
            val stillPendingIds = medicationDao.getPendingSync().map { it.id }.toSet()
            val targetLocalProfileId = profileId ?: DefaultProfileIds.LOCAL_DEFAULT
            if (profileId.isNullOrBlank()) {
                medicationDao.clearSynced()
            } else {
                medicationDao.clearSyncedForProfile(profileId)
            }
            val remoteEntities = remoteList
                .filter { it.id !in stillPendingIds }
                .map { dto ->
                    cacheLifestyleTriggers(dto, now)
                    fromRemoteDto(dto, targetLocalProfileId, now)
                }
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

    private suspend fun cacheLifestyleTriggers(dto: MedicationResponseDto, nowMs: Long) {
        val dao = lifestyleTriggerDao ?: return
        if (dto.lifestyleTriggers.isEmpty() || dto.canonicalSaltKeys.isEmpty()) return
        val entities = mutableListOf<CachedLifestyleTriggerEntity>()
        for (salt in dto.canonicalSaltKeys) {
            for (trigger in dto.lifestyleTriggers) {
                entities.add(
                    CachedLifestyleTriggerEntity(
                        id = "$salt:${trigger.type}",
                        canonicalSalt = salt,
                        triggerType = trigger.type,
                        severity = trigger.severity,
                        shortNote = trigger.shortNote,
                        sourceSection = trigger.sourceSection,
                        updatedAtEpochMs = nowMs,
                    ),
                )
            }
        }
        dao.upsertAll(entities)
    }

    private fun fromRemoteDto(
        dto: MedicationResponseDto,
        fallbackProfileId: String,
        timestampMs: Long,
    ): MedicationEntity = MedicationEntity(
        id = dto.id,
        profileId = dto.profileId.ifBlank { fallbackProfileId },
        brandMappingId = dto.brandMappingId,
        displayName = dto.displayName,
        genericSaltsJson = json.encodeToString(dto.genericSalts),
        canonicalSaltKeysCsv = dto.canonicalSaltKeys.joinToString(","),
        dosageSchedule = dto.dosageSchedule,
        isActive = dto.isActive,
        syncStatus = SyncStatus.SYNCED,
        lifestyleTriggersJson = json.encodeToString(dto.lifestyleTriggers),
        hasActiveRecall = dto.hasActiveRecall,
        activeRecallsJson = json.encodeToString(dto.activeRecalls),
        updatedAtEpochMs = timestampMs,
    )

    private fun decodeSalts(rawJson: String): List<SaltComponentDto> =
        runCatching { json.decodeFromString<List<SaltComponentDto>>(rawJson) }.getOrDefault(emptyList())

    private fun decodeTriggers(rawJson: String): List<LifestyleTriggerDto> =
        runCatching { json.decodeFromString<List<LifestyleTriggerDto>>(rawJson) }.getOrDefault(emptyList())

    private fun decodeRecalls(rawJson: String): List<MedicationRecallFlagDto> =
        runCatching { json.decodeFromString<List<MedicationRecallFlagDto>>(rawJson) }.getOrDefault(emptyList())

    private fun toDomain(entity: MedicationEntity): CabinetMedication = CabinetMedication(
        id = entity.id,
        profileId = entity.profileId,
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
        lifestyleTriggers = decodeTriggers(entity.lifestyleTriggersJson),
        hasActiveRecall = entity.hasActiveRecall,
        activeRecalls = decodeRecalls(entity.activeRecallsJson),
        updatedAtEpochMs = entity.updatedAtEpochMs,
    )
}
