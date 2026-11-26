package com.pillscope.app.data.repository

import android.content.Context
import com.pillscope.app.data.local.dao.DoseScheduleDao
import com.pillscope.app.data.local.dao.RecallAlertDao
import com.pillscope.app.data.local.entity.CachedDoseScheduleEntity
import com.pillscope.app.data.local.entity.CachedRecallAlertEntity
import com.pillscope.app.data.local.entity.DefaultProfileIds
import com.pillscope.app.data.remote.ApiCallError
import com.pillscope.app.data.remote.PillScopeApi
import com.pillscope.app.data.remote.dto.ProfileRecallAlertItemDto
import com.pillscope.app.data.remote.dto.ProfileRecallsResponseDto
import com.pillscope.app.data.remote.dto.ProfileScheduleResponseDto
import com.pillscope.app.data.remote.toApiCallError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ScheduleRefreshResult(
    val schedule: ProfileScheduleResponseDto?,
    val error: ApiCallError? = null,
)

data class RecallRefreshResult(
    val recalls: ProfileRecallsResponseDto?,
    val error: ApiCallError? = null,
)

@Singleton
class DoseAdherenceStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile
    private var loadedDate: LocalDate = LocalDate.now()
    private val _takenKeys = MutableStateFlow(loadTodayKeys(loadedDate))
    val takenKeys: StateFlow<Set<String>> = _takenKeys.asStateFlow()

    fun refreshIfDayChanged() {
        val today = LocalDate.now()
        if (today != loadedDate) {
            loadedDate = today
            val fresh = loadTodayKeys(today)
            prefs.edit().putStringSet(KEY_TAKEN_SET, fresh).apply()
            _takenKeys.value = fresh
        }
    }

    fun doseKey(profileId: String, medicationId: String, slot: String, doseNumber: Int): String {
        refreshIfDayChanged()
        return "${LocalDate.now()}:$profileId:$medicationId:$slot:$doseNumber"
    }

    fun toggleDoseTaken(profileId: String, medicationId: String, slot: String, doseNumber: Int) {
        refreshIfDayChanged()
        val key = doseKey(profileId, medicationId, slot, doseNumber)
        val todayPrefix = "${LocalDate.now()}:"
        val current = _takenKeys.value.filter { it.startsWith(todayPrefix) }.toMutableSet()
        if (key in current) current.remove(key) else current.add(key)
        prefs.edit().putStringSet(KEY_TAKEN_SET, current).apply()
        _takenKeys.value = current
    }

    fun clearAll() {
        loadedDate = LocalDate.now()
        prefs.edit().remove(KEY_TAKEN_SET).apply()
        _takenKeys.value = emptySet()
    }

    private fun loadTodayKeys(today: LocalDate = LocalDate.now()): Set<String> {
        val todayPrefix = "$today:"
        return prefs.getStringSet(KEY_TAKEN_SET, emptySet())
            .orEmpty()
            .filter { it.startsWith(todayPrefix) }
            .toSet()
    }

    private companion object {
        const val PREFS_NAME = "pillscope_dose_adherence"
        const val KEY_TAKEN_SET = "taken_keys_v1"
    }
}

@Singleton
class ScheduleAndRecallRepository @Inject constructor(
    private val doseScheduleDao: DoseScheduleDao,
    private val recallAlertDao: RecallAlertDao,
    private val api: PillScopeApi,
    private val json: Json,
) {
    private fun isServerProfileId(profileId: String): Boolean =
        profileId.isNotBlank() &&
            !profileId.startsWith("local-") &&
            profileId != DefaultProfileIds.LOCAL_DEFAULT

    fun observeSchedule(profileId: String): Flow<ProfileScheduleResponseDto?> =
        doseScheduleDao.observeForProfile(profileId).map { entity ->
            entity?.let {
                runCatching {
                    json.decodeFromString<ProfileScheduleResponseDto>(it.scheduleJson)
                }.getOrNull()
            }
        }

    suspend fun refreshSchedule(profileId: String): ProfileScheduleResponseDto? =
        refreshScheduleDetailed(profileId).schedule

    suspend fun refreshScheduleDetailed(profileId: String): ScheduleRefreshResult {
        if (!isServerProfileId(profileId)) {
            val cached = doseScheduleDao.getForProfile(profileId)?.let {
                runCatching { json.decodeFromString<ProfileScheduleResponseDto>(it.scheduleJson) }.getOrNull()
            }
            return ScheduleRefreshResult(
                schedule = cached,
                error = ApiCallError.Offline("Sync your profile online to compute a personalized schedule."),
            )
        }

        val remoteResult = runCatching { api.getProfileSchedule(profileId) }
        return remoteResult.fold(
            onSuccess = { remote ->
                doseScheduleDao.upsert(
                    CachedDoseScheduleEntity(
                        profileId = profileId,
                        profileName = remote.profileName,
                        safetyBannerLocked = remote.safetyBannerLocked,
                        scheduleJson = json.encodeToString(remote),
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                ScheduleRefreshResult(schedule = remote)
            },
            onFailure = { exc ->
                val cached = doseScheduleDao.getForProfile(profileId)?.let {
                    runCatching {
                        json.decodeFromString<ProfileScheduleResponseDto>(it.scheduleJson)
                    }.getOrNull()
                }
                ScheduleRefreshResult(schedule = cached, error = exc.toApiCallError(json))
            },
        )
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

    suspend fun refreshRecalls(profileId: String): ProfileRecallsResponseDto? =
        refreshRecallsDetailed(profileId).recalls

    suspend fun refreshRecallsDetailed(profileId: String): RecallRefreshResult {
        if (!isServerProfileId(profileId)) {
            return RecallRefreshResult(
                recalls = null,
                error = ApiCallError.Offline("Sign in and sync online to check FDA recall alerts."),
            )
        }
        val remoteResult = runCatching { api.getProfileRecalls(profileId, activeOnly = true) }
        return remoteResult.fold(
            onSuccess = { remote ->
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
                RecallRefreshResult(recalls = remote)
            },
            onFailure = { exc ->
                RecallRefreshResult(recalls = null, error = exc.toApiCallError(json))
            },
        )
    }
}
