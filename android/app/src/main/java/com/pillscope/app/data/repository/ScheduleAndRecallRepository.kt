package com.pillscope.app.data.repository

import com.pillscope.app.data.local.dao.DoseScheduleDao
import com.pillscope.app.data.local.dao.RecallAlertDao
import com.pillscope.app.data.local.entity.CachedDoseScheduleEntity
import com.pillscope.app.data.local.entity.CachedRecallAlertEntity
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.ProfileRecallsResponseDto
import com.pillscope.app.data.remote.dto.ProfileScheduleResponseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScheduleAndRecallRepository @Inject constructor(
    private val doseScheduleDao: DoseScheduleDao,
    private val recallAlertDao: RecallAlertDao,
    private val api: PillScopeApi,
    private val json: Json,
) {
    fun observeSchedule(profileId: String): Flow<ProfileScheduleResponseDto?> =
        doseScheduleDao.observeForProfile(profileId).map { entity ->
            entity?.let {
                runCatching {
                    json.decodeFromString<ProfileScheduleResponseDto>(it.scheduleJson)
                }.getOrNull()
            }
        }

    suspend fun refreshSchedule(profileId: String): ProfileScheduleResponseDto? {
        val remote = runCatching { api.getProfileSchedule(profileId) }.getOrNull()
        if (remote != null) {
            doseScheduleDao.upsert(
                CachedDoseScheduleEntity(
                    profileId = profileId,
                    profileName = remote.profileName,
                    safetyBannerLocked = remote.safetyBannerLocked,
                    scheduleJson = json.encodeToString(remote),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return remote
        }
        val cached = doseScheduleDao.getForProfile(profileId) ?: return null
        return runCatching {
            json.decodeFromString<ProfileScheduleResponseDto>(cached.scheduleJson)
        }.getOrNull()
    }

    fun observeRecalls(profileId: String): Flow<List<ProfileRecallAlertItemDto>> =
        recallAlertDao.observeForProfile(profileId).map { rows ->
            rows.map { row ->
                val terms = runCatching {
                    json.decodeFromString<List<String>>(row.matchedTermsJson)
                }.getOrDefault(emptyList())
                ProfileRecallAlertItemDto(
                    alertLabel = row.alertLabel,
                    jurisdiction = "US_FDA",
                    recallId = row.recallId,
                    recallNumber = row.recallNumber,
                    classification = row.classification,
                    status = row.status,
                    recallingFirm = row.recallingFirm,
                    productDescription = row.productDescription,
                    reasonForRecall = row.reasonForRecall,
                    reportDate = row.reportDate,
                    sourceUrl = row.sourceUrl,
                    matchedMedicationId = row.matchedMedicationId,
                    matchedMedicationName = row.matchedMedicationName,
                    matchBasis = row.matchBasis,
                    matchedTerms = terms,
                )
            }
        }

    suspend fun refreshRecalls(profileId: String): ProfileRecallsResponseDto? {
        val remote = runCatching { api.getProfileRecalls(profileId, activeOnly = true) }.getOrNull()
            ?: return null
        val now = System.currentTimeMillis()
        recallAlertDao.clearForProfile(profileId)
        val entities = remote.recalls.map { item ->
            CachedRecallAlertEntity(
                id = "$profileId:${item.matchedMedicationId}:${item.recallNumber}",
                profileId = profileId,
                recallId = item.recallId,
                recallNumber = item.recallNumber,
                alertLabel = item.alertLabel,
                classification = item.classification,
                status = item.status,
                recallingFirm = item.recallingFirm,
                productDescription = item.productDescription,
                reasonForRecall = item.reasonForRecall,
                reportDate = item.reportDate,
                sourceUrl = item.sourceUrl,
                matchedMedicationId = item.matchedMedicationId,
                matchedMedicationName = item.matchedMedicationName,
                matchBasis = item.matchBasis,
                matchedTermsJson = json.encodeToString(item.matchedTerms),
                updatedAtEpochMs = now,
            )
        }
        if (entities.isNotEmpty()) {
            recallAlertDao.upsertAll(entities)
        }
        return remote
    }
}
