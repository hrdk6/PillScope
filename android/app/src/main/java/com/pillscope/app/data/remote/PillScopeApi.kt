package com.pillscope.app.data.remote

import com.pillscope.app.data.remote.dto.AuthResponseDto
import com.pillscope.app.data.remote.dto.BrandCandidateDto
import com.pillscope.app.data.remote.dto.DrugResolveRequestDto
import com.pillscope.app.data.remote.dto.DrugResolveResponseDto
import com.pillscope.app.data.remote.dto.DuplicateCheckRequestDto
import com.pillscope.app.data.remote.dto.DuplicateCheckResponseDto
import com.pillscope.app.data.remote.dto.LoginRequestDto
import com.pillscope.app.data.remote.dto.MedicationCreateRequestDto
import com.pillscope.app.data.remote.dto.MedicationCreateResponseDto
import com.pillscope.app.data.remote.dto.MedicationResponseDto
import com.pillscope.app.data.remote.dto.MedicationUpdateRequestDto
import com.pillscope.app.data.remote.dto.RefreshRequestDto
import com.pillscope.app.data.remote.dto.RegisterRequestDto
import com.pillscope.app.data.remote.dto.ScanLogListResponseDto
import com.pillscope.app.data.remote.dto.ScanLogResponseDto
import com.pillscope.app.data.remote.dto.TokenResponseDto
import com.pillscope.app.data.remote.dto.UserResponseDto
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

    @GET("api/v1/auth/me")
    suspend fun getMe(): UserResponseDto

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
        @Query("active_only") activeOnly: Boolean = false,
    ): List<MedicationResponseDto>

    @POST("api/v1/cabinet/medications")
    suspend fun createMedication(@Body body: MedicationCreateRequestDto): MedicationCreateResponseDto

    @PATCH("api/v1/cabinet/medications/{id}")
    suspend fun updateMedication(
        @Path("id") medicationId: String,
        @Body body: MedicationUpdateRequestDto,
    ): MedicationResponseDto

    @DELETE("api/v1/cabinet/medications/{id}")
    suspend fun deleteMedication(@Path("id") medicationId: String): Response<Unit>

    @POST("api/v1/cabinet/check-duplicates")
    suspend fun checkDuplicates(@Body body: DuplicateCheckRequestDto): DuplicateCheckResponseDto

    @GET("api/v1/scans")
    suspend fun listScans(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ScanLogListResponseDto

    @GET("api/v1/scans/{scan_id}")
    suspend fun getScan(@Path("scan_id") scanId: String): ScanLogResponseDto
}
