package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.dao.ProfileDao
import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.local.entity.ProfileEntity
import com.pillscope.app.data.local.entity.SyncStatus
import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.AllergyEntryDto
import com.pillscope.app.data.remote.dto.ProfileCreateRequestDto
import com.pillscope.app.data.remote.dto.ProfileResponseDto
import com.pillscope.app.data.remote.dto.ProfileUpdateRequestDto
import com.pillscope.app.data.remote.toApiCallError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class UserProfile(
    val id: String,
    val userId: String,
    val name: String,
    val relationship: String,
    val allergies: List<String>,
    val allergyEntries: List<AllergyEntryDto> = allergies.map { AllergyEntryDto(allergen = it) },
    val notes: String?,
    val isDefault: Boolean,
    val syncStatus: String = SyncStatus.SYNCED,
    val updatedAtEpochMs: Long,
) {
    val isLocalOnly: Boolean
        get() = id == DefaultProfileIds.LOCAL_DEFAULT || id.startsWith("local-")
}

data class ProfileSyncOutcome(
    val profiles: List<UserProfile>,
    val error: ApiCallError? = null,
)

data class ProfileMutationOutcome(
    val profile: UserProfile? = null,
    val succeeded: Boolean = false,
    val syncedWithServer: Boolean = false,
    val error: ApiCallError? = null,
)

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val medicationDao: MedicationDao,
    private val scanReportDao: ScanReportDao,
    private val api: PillScopeApi,
    private val json: Json,
) {
    private val _selectedProfileId = MutableStateFlow(DefaultProfileIds.LOCAL_DEFAULT)
    val selectedProfileId: StateFlow<String> = _selectedProfileId.asStateFlow()

    fun isServerProfileId(profileId: String?): Boolean =
        !profileId.isNullOrBlank() &&
            !profileId.startsWith("local-") &&
            profileId != DefaultProfileIds.LOCAL_DEFAULT

    fun observeProfiles(): Flow<List<UserProfile>> =
        profileDao.observeProfiles().map { list ->
            if (list.isEmpty()) {
                listOf(fallbackProfile())
            } else {
                list.map(::toDomain)
            }
        }

    fun observeActiveProfile(): Flow<UserProfile> =
        combine(observeProfiles(), selectedProfileId) { profiles, selectedId ->
            profiles.firstOrNull { it.id == selectedId }
                ?: profiles.firstOrNull { it.isDefault }
                ?: profiles.first()
        }

    fun selectProfile(profileId: String) {
        if (profileId.isNotBlank()) {
            _selectedProfileId.value = profileId
        }
    }

    suspend fun ensureLocalDefaultProfile(): UserProfile {
        val existing = profileDao.getAllProfiles()
        if (existing.isNotEmpty()) {
            val active = existing.firstOrNull { it.id == _selectedProfileId.value }
                ?: existing.firstOrNull { it.isDefault }
                ?: existing.first()
            _selectedProfileId.value = active.id
            return toDomain(active)
        }
        val entity = ProfileEntity(
            id = DefaultProfileIds.LOCAL_DEFAULT,
            userId = "",
            name = "Self",
            relationship = "self",
            allergiesJson = "[]",
            notes = null,
            isDefault = true,
            syncStatus = SyncStatus.SYNCED,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        profileDao.upsert(entity)
        _selectedProfileId.value = entity.id
        return toDomain(entity)
    }

    suspend fun refreshProfiles(): ProfileSyncOutcome {
        ensureLocalDefaultProfile()
        val now = System.currentTimeMillis()
        var syncError: ApiCallError? = null

        // Push pending offline profiles & updates before pulling remote list so nothing is lost.
        val pendingLocal = profileDao.getPendingSync()
        for (pending in pendingLocal) {
            when (pending.syncStatus) {
                SyncStatus.PENDING_CREATE -> {
                    val entries = decodeAllergyEntries(pending.allergiesJson)
                    val created = runCatching {
                        api.createProfile(
                            ProfileCreateRequestDto(
                                name = pending.name,
                                relationship = pending.relationship,
                                allergiesJson = encodeAllergyElements(entries),
                                notes = pending.notes,
                                isDefault = false,
                            ),
                        )
                    }
                    created.fold(
                        onSuccess = { remoteDto ->
                            val synced = fromRemoteDto(remoteDto, now)
                            medicationDao.reassignProfileId(pending.id, synced.id)
                            scanReportDao.reassignProfileId(pending.id, synced.id)
                            profileDao.deleteById(pending.id)
                            profileDao.upsert(synced)
                            if (_selectedProfileId.value == pending.id) {
                                _selectedProfileId.value = synced.id
                            }
                        },
                        onFailure = { exc ->
                            if (syncError == null) syncError = exc.toApiCallError(json)
                        },
                    )
                }

                SyncStatus.PENDING_UPDATE -> {
                    if (isServerProfileId(pending.id)) {
                        val entries = decodeAllergyEntries(pending.allergiesJson)
                        val updated = runCatching {
                            api.updateProfile(
                                profileId = pending.id,
                                body = ProfileUpdateRequestDto(
                                    name = pending.name,
                                    relationship = pending.relationship,
                                    allergiesJson = encodeAllergyElements(entries),
                                    notes = pending.notes,
                                ),
                            )
                        }
                        updated.fold(
                            onSuccess = { remoteDto ->
                                profileDao.upsert(fromRemoteDto(remoteDto, now))
                            },
                            onFailure = { exc ->
                                if (syncError == null) syncError = exc.toApiCallError(json)
                            },
                        )
                    }
                }
            }
        }

        val remoteResult = runCatching { api.listProfiles() }
        val remote = remoteResult.getOrElse { exc ->
            val current = profileDao.getAllProfiles().map(::toDomain)
            return ProfileSyncOutcome(profiles = current, error = exc.toApiCallError(json))
        }
        if (remote.isEmpty()) {
            return ProfileSyncOutcome(profiles = profileDao.getAllProfiles().map(::toDomain), error = syncError)
        }

        val entities = remote.map { fromRemoteDto(it, now) }
        val serverDefault = entities.firstOrNull { it.isDefault } ?: entities.first()

        // If the user added offline data under LOCAL_DEFAULT, migrate it to their server default profile.
        val localDefault = profileDao.getById(DefaultProfileIds.LOCAL_DEFAULT)
        if (localDefault != null) {
            val localDefaultEntries = decodeAllergyEntries(localDefault.allergiesJson)
            if (localDefault.syncStatus == SyncStatus.PENDING_UPDATE && localDefaultEntries.isNotEmpty()) {
                runCatching {
                    api.updateProfile(
                        profileId = serverDefault.id,
                        body = ProfileUpdateRequestDto(
                            allergiesJson = encodeAllergyElements(localDefaultEntries),
                        ),
                    )
                }.onSuccess { updatedDefault ->
                    profileDao.upsert(fromRemoteDto(updatedDefault, now))
                }
            }
            medicationDao.reassignProfileId(DefaultProfileIds.LOCAL_DEFAULT, serverDefault.id)
            scanReportDao.reassignProfileId(DefaultProfileIds.LOCAL_DEFAULT, serverDefault.id)
            profileDao.deleteById(DefaultProfileIds.LOCAL_DEFAULT)
        }

        // Preserve any still-pending local profiles instead of wiping them with clearAll().
        val stillPendingIds = profileDao.getPendingSync().map { it.id }.toSet()
        profileDao.clearSynced()
        profileDao.upsertAll(entities.filter { it.id !in stillPendingIds })

        val allAfter = profileDao.getAllProfiles()
        val currentSelected = _selectedProfileId.value
        val validSelection = allAfter.firstOrNull { it.id == currentSelected }
            ?: allAfter.firstOrNull { it.isDefault }
            ?: allAfter.first()
        _selectedProfileId.value = validSelection.id
        return ProfileSyncOutcome(profiles = allAfter.map(::toDomain), error = syncError)
    }

    suspend fun createProfile(
        name: String,
        relationship: String,
        allergies: List<String>,
        notes: String? = null,
    ): ProfileMutationOutcome {
        val cleanedName = name.trim()
        val cleanedRel = relationship.trim().ifEmpty { "other" }
        val entries = allergies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .map { AllergyEntryDto(allergen = it) }
        val now = System.currentTimeMillis()

        val remoteResult = runCatching {
            api.createProfile(
                ProfileCreateRequestDto(
                    name = cleanedName,
                    relationship = cleanedRel,
                    allergiesJson = encodeAllergyElements(entries),
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    isDefault = false,
                ),
            )
        }

        return remoteResult.fold(
            onSuccess = { remoteDto ->
                val entity = fromRemoteDto(remoteDto, now)
                profileDao.upsert(entity)
                _selectedProfileId.value = entity.id
                ProfileMutationOutcome(
                    profile = toDomain(entity),
                    succeeded = true,
                    syncedWithServer = true,
                )
            },
            onFailure = { exc ->
                val err = exc.toApiCallError(json)
                if (err is ApiCallError.Offline) {
                    val entity = ProfileEntity(
                        id = "local-profile-${UUID.randomUUID()}",
                        userId = "",
                        name = cleanedName,
                        relationship = cleanedRel,
                        allergiesJson = json.encodeToString(entries),
                        notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                        isDefault = false,
                        syncStatus = SyncStatus.PENDING_CREATE,
                        updatedAtEpochMs = now,
                    )
                    profileDao.upsert(entity)
                    _selectedProfileId.value = entity.id
                    ProfileMutationOutcome(
                        profile = toDomain(entity),
                        succeeded = true,
                        syncedWithServer = false,
                        error = err,
                    )
                } else {
                    ProfileMutationOutcome(succeeded = false, syncedWithServer = false, error = err)
                }
            },
        )
    }

    suspend fun updateProfileDetails(
        profileId: String,
        name: String? = null,
        relationship: String? = null,
        allergies: List<AllergyEntryDto>? = null,
        setAsDefault: Boolean? = null,
    ): ProfileMutationOutcome {
        val existing = profileDao.getById(profileId)
            ?: return ProfileMutationOutcome(succeeded = false)
        val now = System.currentTimeMillis()
        val nextName = name?.trim()?.takeIf { it.isNotEmpty() } ?: existing.name
        val nextRel = relationship?.trim()?.takeIf { it.isNotEmpty() } ?: existing.relationship
        val nextEntries = allergies ?: decodeAllergyEntries(existing.allergiesJson)

        if (!isServerProfileId(profileId)) {
            val nextStatus = if (existing.syncStatus == SyncStatus.PENDING_CREATE) {
                SyncStatus.PENDING_CREATE
            } else {
                SyncStatus.PENDING_UPDATE
            }
            val updated = existing.copy(
                name = nextName,
                relationship = nextRel,
                allergiesJson = json.encodeToString(nextEntries),
                syncStatus = nextStatus,
                updatedAtEpochMs = now,
            )
            profileDao.upsert(updated)
            return ProfileMutationOutcome(
                profile = toDomain(updated),
                succeeded = true,
                syncedWithServer = false,
                error = ApiCallError.Offline("Saved locally; will sync when signed in and online."),
            )
        }

        val remoteResult = runCatching {
            api.updateProfile(
                profileId = profileId,
                body = ProfileUpdateRequestDto(
                    name = name?.trim()?.takeIf { it.isNotEmpty() },
                    relationship = relationship?.trim()?.takeIf { it.isNotEmpty() },
                    allergiesJson = allergies?.let(::encodeAllergyElements),
                    isDefault = setAsDefault,
                ),
            )
        }

        return remoteResult.fold(
            onSuccess = { remoteDto ->
                if (setAsDefault == true) {
                    val others = profileDao.getAllProfiles().filter { it.id != profileId && it.isDefault }
                    others.forEach { profileDao.upsert(it.copy(isDefault = false)) }
                }
                val entity = fromRemoteDto(remoteDto, now)
                profileDao.upsert(entity)
                ProfileMutationOutcome(
                    profile = toDomain(entity),
                    succeeded = true,
                    syncedWithServer = true,
                )
            },
            onFailure = { exc ->
                val err = exc.toApiCallError(json)
                if (err is ApiCallError.Offline && setAsDefault != true) {
                    val updated = existing.copy(
                        name = nextName,
                        relationship = nextRel,
                        allergiesJson = json.encodeToString(nextEntries),
                        syncStatus = SyncStatus.PENDING_UPDATE,
                        updatedAtEpochMs = now,
                    )
                    profileDao.upsert(updated)
                    ProfileMutationOutcome(
                        profile = toDomain(updated),
                        succeeded = true,
                        syncedWithServer = false,
                        error = err,
                    )
                } else {
                    ProfileMutationOutcome(succeeded = false, syncedWithServer = false, error = err)
                }
            },
        )
    }

    suspend fun updateProfileAllergies(
        profileId: String,
        allergies: List<String>,
    ): ProfileMutationOutcome {
        val existing = profileDao.getById(profileId)
            ?: return ProfileMutationOutcome(succeeded = false)
        val existingByKey = decodeAllergyEntries(existing.allergiesJson)
            .associateBy { it.allergen.trim().lowercase() }
        // Preserve structured reaction/severity metadata on allergies that remain in the list.
        val merged = allergies
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .map { label ->
                existingByKey[label.lowercase()]?.copy(allergen = label)
                    ?: AllergyEntryDto(allergen = label)
            }
        return updateProfileDetails(profileId = profileId, allergies = merged)
    }

    suspend fun deleteProfile(profileId: String): ProfileMutationOutcome {
        val existing = profileDao.getById(profileId)
            ?: return ProfileMutationOutcome(succeeded = false)
        if (existing.isDefault) {
            return ProfileMutationOutcome(
                succeeded = false,
                error = ApiCallError.Validation("Cannot delete the default profile. Promote another profile first."),
            )
        }

        if (isServerProfileId(profileId)) {
            val remoteResult = runCatching {
                val resp = api.deleteProfile(profileId)
                if (!resp.isSuccessful && resp.code() != 404) {
                    throw retrofit2.HttpException(resp)
                }
            }
            val err = remoteResult.exceptionOrNull()?.toApiCallError(json)
            if (err != null) {
                return ProfileMutationOutcome(succeeded = false, error = err)
            }
        }

        profileDao.deleteById(profileId)
        if (_selectedProfileId.value == profileId) {
            val defaultProfile = profileDao.getDefaultProfile()
            _selectedProfileId.value = defaultProfile?.id ?: DefaultProfileIds.LOCAL_DEFAULT
        }
        return ProfileMutationOutcome(succeeded = true, syncedWithServer = isServerProfileId(profileId))
    }

    private fun encodeAllergyElements(entries: List<AllergyEntryDto>): List<JsonElement> =
        entries.map { entry ->
            if (entry.reaction.isNullOrBlank() && entry.severity.isNullOrBlank()) {
                JsonPrimitive(entry.allergen)
            } else {
                json.encodeToJsonElement(entry)
            }
        }

    private fun decodeAllergyEntries(rawJson: String): List<AllergyEntryDto> {
        val structured = runCatching { json.decodeFromString<List<AllergyEntryDto>>(rawJson) }.getOrNull()
        if (structured != null) return structured
        val legacyStrings = runCatching { json.decodeFromString<List<String>>(rawJson) }.getOrDefault(emptyList())
        return legacyStrings.map { AllergyEntryDto(allergen = it) }
    }

    private fun fromRemoteDto(dto: ProfileResponseDto, nowMs: Long): ProfileEntity = ProfileEntity(
        id = dto.id,
        userId = dto.userId,
        name = dto.name,
        relationship = dto.relationship,
        allergiesJson = json.encodeToString(dto.extractedAllergyEntries()),
        notes = dto.notes,
        isDefault = dto.isDefault,
        syncStatus = SyncStatus.SYNCED,
        updatedAtEpochMs = nowMs,
    )

    private fun toDomain(entity: ProfileEntity): UserProfile {
        val entries = decodeAllergyEntries(entity.allergiesJson)
        return UserProfile(
            id = entity.id,
            userId = entity.userId,
            name = entity.name,
            relationship = entity.relationship,
            allergies = entries.map { it.allergen },
            allergyEntries = entries,
            notes = entity.notes,
            isDefault = entity.isDefault,
            syncStatus = entity.syncStatus,
            updatedAtEpochMs = entity.updatedAtEpochMs,
        )
    }

    private fun fallbackProfile(): UserProfile = UserProfile(
        id = DefaultProfileIds.LOCAL_DEFAULT,
        userId = "",
        name = "Self",
        relationship = "self",
        allergies = emptyList(),
        allergyEntries = emptyList(),
        notes = null,
        isDefault = true,
        syncStatus = SyncStatus.SYNCED,
        updatedAtEpochMs = 0L,
    )
}
