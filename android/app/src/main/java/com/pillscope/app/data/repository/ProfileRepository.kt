package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.ProfileDao
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.local.entity.ProfileEntity
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.ProfileCreateRequestDto
import com.pillscope.app.data.remote.dto.ProfileResponseDto
import com.pillscope.app.data.remote.dto.ProfileUpdateRequestDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class UserProfile(
    val id: String,
    val userId: String,
    val name: String,
    val relationship: String,
    val allergies: List<String>,
    val notes: String?,
    val isDefault: Boolean,
    val updatedAtEpochMs: Long,
)

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val api: PillScopeApi,
    private val json: Json,
) {
    private val _selectedProfileId = MutableStateFlow(DefaultProfileIds.LOCAL_DEFAULT)
    val selectedProfileId: StateFlow<String> = _selectedProfileId.asStateFlow()

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
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        profileDao.upsert(entity)
        _selectedProfileId.value = entity.id
        return toDomain(entity)
    }

    suspend fun refreshProfiles(): List<UserProfile> {
        ensureLocalDefaultProfile()
        val remote = runCatching { api.listProfiles() }.getOrNull() ?: return profileDao.getAllProfiles().map(::toDomain)
        if (remote.isEmpty()) return profileDao.getAllProfiles().map(::toDomain)

        val now = System.currentTimeMillis()
        val entities = remote.map { fromRemoteDto(it, now) }
        profileDao.clearAll()
        profileDao.upsertAll(entities)

        val currentSelected = _selectedProfileId.value
        val validSelection = entities.firstOrNull { it.id == currentSelected }
            ?: entities.firstOrNull { it.isDefault }
            ?: entities.first()
        _selectedProfileId.value = validSelection.id
        return entities.map(::toDomain)
    }

    suspend fun createProfile(
        name: String,
        relationship: String,
        allergies: List<String>,
        notes: String? = null,
    ): UserProfile {
        val cleanedName = name.trim()
        val cleanedRel = relationship.trim().ifEmpty { "other" }
        val cleanedAllergies = allergies.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val now = System.currentTimeMillis()

        val remote = runCatching {
            api.createProfile(
                ProfileCreateRequestDto(
                    name = cleanedName,
                    relationship = cleanedRel,
                    allergiesJson = cleanedAllergies,
                    notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                    isDefault = false,
                ),
            )
        }.getOrNull()

        val entity = if (remote != null) {
            fromRemoteDto(remote, now)
        } else {
            ProfileEntity(
                id = "local-profile-${UUID.randomUUID()}",
                userId = "",
                name = cleanedName,
                relationship = cleanedRel,
                allergiesJson = json.encodeToString(cleanedAllergies),
                notes = notes?.trim()?.takeIf { it.isNotEmpty() },
                isDefault = false,
                updatedAtEpochMs = now,
            )
        }
        profileDao.upsert(entity)
        _selectedProfileId.value = entity.id
        return toDomain(entity)
    }

    suspend fun updateProfileAllergies(
        profileId: String,
        allergies: List<String>,
    ): UserProfile? {
        val existing = profileDao.getById(profileId) ?: return null
        val cleaned = allergies.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val now = System.currentTimeMillis()

        val remote = runCatching {
            api.updateProfile(
                profileId = profileId,
                body = ProfileUpdateRequestDto(allergiesJson = cleaned),
            )
        }.getOrNull()

        val updated = if (remote != null) {
            fromRemoteDto(remote, now)
        } else {
            existing.copy(
                allergiesJson = json.encodeToString(cleaned),
                updatedAtEpochMs = now,
            )
        }
        profileDao.upsert(updated)
        return toDomain(updated)
    }

    suspend fun deleteProfile(profileId: String): Boolean {
        val existing = profileDao.getById(profileId) ?: return false
        if (existing.isDefault) return false

        runCatching { api.deleteProfile(profileId) }
        profileDao.deleteById(profileId)

        if (_selectedProfileId.value == profileId) {
            val defaultProfile = profileDao.getDefaultProfile()
            _selectedProfileId.value = defaultProfile?.id ?: DefaultProfileIds.LOCAL_DEFAULT
        }
        return true
    }

    private fun fromRemoteDto(dto: ProfileResponseDto, nowMs: Long): ProfileEntity = ProfileEntity(
        id = dto.id,
        userId = dto.userId,
        name = dto.name,
        relationship = dto.relationship,
        allergiesJson = json.encodeToString(dto.extractedAllergyLabels()),
        notes = dto.notes,
        isDefault = dto.isDefault,
        updatedAtEpochMs = nowMs,
    )

    private fun toDomain(entity: ProfileEntity): UserProfile {
        val allergies = runCatching {
            json.decodeFromString<List<String>>(entity.allergiesJson)
        }.getOrDefault(emptyList())
        return UserProfile(
            id = entity.id,
            userId = entity.userId,
            name = entity.name,
            relationship = entity.relationship,
            allergies = allergies,
            notes = entity.notes,
            isDefault = entity.isDefault,
            updatedAtEpochMs = entity.updatedAtEpochMs,
        )
    }

    private fun fallbackProfile(): UserProfile = UserProfile(
        id = DefaultProfileIds.LOCAL_DEFAULT,
        userId = "",
        name = "Self",
        relationship = "self",
        allergies = emptyList(),
        notes = null,
        isDefault = true,
        updatedAtEpochMs = 0L,
    )
}
