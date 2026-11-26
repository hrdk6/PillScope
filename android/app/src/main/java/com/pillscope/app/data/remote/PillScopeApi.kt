package com.pillscope.app.data.remote

import com.pillscope.app.data.remote.dto.AssistantAskRequestDto
import com.pillscope.app.data.remote.dto.AssistantAskResponseDto
import com.pillscope.app.data.remote.dto.AssistantTtsRequestDto
import com.pillscope.app.data.remote.dto.AuthResponseDto
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.DrugResolveRequestDto
import com.pillscope.app.data.remote.dto.DrugResolveResponseDto
import com.pillscope.app.data.remote.dto.DuplicateCheckRequestDto
import com.pillscope.app.data.remote.dto.DuplicateCheckResponseDto
import com.pillscope.app.data.remote.dto.LoginRequestDto
import com.pillscope.app.data.remote.dto.LogoutRequestDto
import com.pillscope.app.data.remote.dto.MedicationCreateRequestDto
import com.pillscope.app.data.remote.dto.MedicationCreateResponseDto
import com.pillscope.app.data.remote.dto.MedicationResponseDto
import com.pillscope.app.data.remote.dto.MedicationUpdateRequestDto
import com.pillscope.app.data.remote.dto.ProfileCreateRequestDto
import com.pillscope.app.data.remote.dto.ProfileRecallsResponseDto
import com.pillscope.app.data.remote.dto.ProfileResponseDto
import com.pillscope.app.data.remote.dto.ProfileScheduleResponseDto
import com.pillscope.app.data.remote.dto.ProfileUpdateRequestDto
import com.pillscope.app.data.remote.dto.RefreshRequestDto
import com.pillscope.app.data.remote.dto.RegisterRequestDto
import com.pillscope.app.data.remote.dto.ScanLogListResponseDto
import com.pillscope.app.data.remote.dto.ScanLogResponseDto
import com.pillscope.app.data.remote.dto.SupportedLanguagesResponseDto
import com.pillscope.app.data.remote.dto.TokenResponseDto
import com.pillscope.app.data.remote.dto.UserResponseDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface PillScopeApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequestDto): TokenResponseDto

    @POST("api/v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequestDto): Response<Unit>

    @GET("api/v1/auth/me")
    suspend fun getMe(): UserResponseDto

    @DELETE("api/v1/auth/me")
    suspend fun deleteMe(): Response<Unit>

    @GET("api/v1/profiles")
    suspend fun listProfiles(): List<ProfileResponseDto>

    @POST("api/v1/profiles")
    suspend fun createProfile(@Body body: ProfileCreateRequestDto): ProfileResponseDto

    @GET("api/v1/profiles/{profile_id}")
    suspend fun getProfile(@Path("profile_id") profileId: String): ProfileResponseDto

    @PATCH("api/v1/profiles/{profile_id}")
    suspend fun updateProfile(
        @Path("profile_id") profileId: String,
        @Body body: ProfileUpdateRequestDto,
    ): ProfileResponseDto

    @DELETE("api/v1/profiles/{profile_id}")
    suspend fun deleteProfile(@Path("profile_id") profileId: String): Response<Unit>

    @GET("api/v1/profiles/{profile_id}/schedule")
    suspend fun getProfileSchedule(
        @Path("profile_id") profileId: String,
    ): ProfileScheduleResponseDto

    @GET("api/v1/profiles/{profile_id}/recalls")
    suspend fun getProfileRecalls(
        @Path("profile_id") profileId: String,
        @Query("active_only") activeOnly: Boolean = true,
    ): ProfileRecallsResponseDto

    @POST("api/v1/drugs/resolve")
    suspend fun resolveDrug(@Body body: DrugResolveRequestDto): DrugResolveResponseDto

    @GET("api/v1/drugs/search")
    suspend fun searchDrugs(
        @Query("q") query: String,
        @Query("country") countryCode: String? = "IN",
        @Query("limit") limit: Int = 10,
    ): List<BrandCandidateDto>

    @GET("api/v1/cabinet/medications")
    suspend fun listMedications(
        @Query("profile_id") profileId: String? = null,
        @Query("active_only") activeOnly: Boolean = false,
    ): List<MedicationResponseDto>

    @GET("api/v1/cabinet/medications/{id}")
    suspend fun getMedicationDetail(
        @Path("id") medicationId: String,
        @Query("profile_id") profileId: String? = null,
    ): MedicationResponseDto

    @POST("api/v1/cabinet/medications")
    suspend fun createMedication(@Body body: MedicationCreateRequestDto): MedicationCreateResponseDto

    @PATCH("api/v1/cabinet/medications/{id}")
    suspend fun updateMedication(
        @Path("id") medicationId: String,
        @Body body: MedicationUpdateRequestDto,
        @Query("profile_id") profileId: String? = null,
    ): MedicationResponseDto

    @DELETE("api/v1/cabinet/medications/{id}")
    suspend fun deleteMedication(
        @Path("id") medicationId: String,
        @Query("profile_id") profileId: String? = null,
    ): Response<Unit>

    @POST("api/v1/cabinet/check-duplicates")
    suspend fun checkDuplicates(@Body body: DuplicateCheckRequestDto): DuplicateCheckResponseDto

    @GET("api/v1/scans")
    suspend fun listScans(
        @Query("profile_id") profileId: String? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ScanLogListResponseDto

    @GET("api/v1/scans/{scan_id}")
    suspend fun getScan(@Path("scan_id") scanId: String): ScanLogResponseDto

    @GET("api/v1/assistant/languages")
    suspend fun listSupportedLanguages(): SupportedLanguagesResponseDto

    @POST("api/v1/assistant/ask")
    suspend fun askAssistant(@Body body: AssistantAskRequestDto): AssistantAskResponseDto

    @POST("api/v1/assistant/tts")
    suspend fun synthesizeAssistantSpeech(@Body body: AssistantTtsRequestDto): Response<ResponseBody>
}
