package com.pillscope.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequestDto(
    val email: String,
    val password: String,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
)

@Serializable
data class RefreshRequestDto(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class TokenResponseDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "bearer",
    @SerialName("expires_in") val expiresIn: Int = 1800,
)

@Serializable
data class UserResponseDto(
    val id: String,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AuthResponseDto(
    val user: UserResponseDto,
    val tokens: TokenResponseDto,
)

@Serializable
data class SaltComponentDto(
    @SerialName("canonical_salt") val canonicalSalt: String,
    @SerialName("strength_mg") val strengthMg: Double? = null,
    @SerialName("strength_text") val strengthText: String? = null,
    val rxcui: String? = null,
)

@Serializable
data class DrugResolveRequestDto(
    val query: String,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val limit: Int = 5,
)

@Serializable
data class BrandCandidateDto(
    @SerialName("brand_mapping_id") val brandMappingId: String? = null,
    @SerialName("brand_name") val brandName: String,
    val manufacturer: String? = null,
    @SerialName("country_code") val countryCode: String = "IN",
    @SerialName("dosage_form") val dosageForm: String? = null,
    @SerialName("generic_salts") val genericSalts: List<SaltComponentDto> = emptyList(),
    @SerialName("canonical_salt_keys") val canonicalSaltKeys: List<String> = emptyList(),
    @SerialName("match_strategy") val matchStrategy: String = "exact_brand",
    val confidence: Double = 1.0,
)

@Serializable
data class DrugResolveResponseDto(
    val query: String,
    @SerialName("normalized_query") val normalizedQuery: String,
    val candidates: List<BrandCandidateDto> = emptyList(),
    @SerialName("extracted_salts") val extractedSalts: List<SaltComponentDto> = emptyList(),
)

@Serializable
data class DuplicateSaltAlertDto(
    @SerialName("canonical_salt") val canonicalSalt: String,
    @SerialName("existing_medication_id") val existingMedicationId: String,
    @SerialName("existing_display_name") val existingDisplayName: String,
    val reason: String = "DUPLICATE_ACTIVE_INGREDIENT",
)

@Serializable
data class MedicationCreateRequestDto(
    @SerialName("brand_mapping_id") val brandMappingId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("generic_salts") val genericSalts: List<SaltComponentDto>? = null,
    @SerialName("dosage_schedule") val dosageSchedule: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class MedicationUpdateRequestDto(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("dosage_schedule") val dosageSchedule: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class MedicationResponseDto(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("brand_mapping_id") val brandMappingId: String? = null,
    @SerialName("display_name") val displayName: String,
    @SerialName("generic_salts") val genericSalts: List<SaltComponentDto> = emptyList(),
    @SerialName("canonical_salt_keys") val canonicalSaltKeys: List<String> = emptyList(),
    @SerialName("dosage_schedule") val dosageSchedule: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class MedicationCreateResponseDto(
    val medication: MedicationResponseDto,
    @SerialName("overall_risk") val overallRisk: String = "NONE",
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
)

@Serializable
data class DuplicateCheckRequestDto(
    @SerialName("brand_name") val brandName: String? = null,
    @SerialName("confirmed_salts") val confirmedSalts: List<String>? = null,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
)

@Serializable
data class DuplicateCheckResponseDto(
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String = "NONE",
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
)

@Serializable
data class EdgeQualityMetricsDto(
    @SerialName("blur_score") val blurScore: Float,
    @SerialName("glare_ratio") val glareRatio: Float,
    @SerialName("crop_confidence") val cropConfidence: Float,
)

@Serializable
data class ScanAnalyzeRequestDto(
    @SerialName("input_mode") val inputMode: String = "CAMERA_OCR",
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("confirmed_salts") val confirmedSalts: List<String>? = null,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
    @SerialName("edge_quality_metrics") val edgeQualityMetrics: EdgeQualityMetricsDto? = null,
)

@Serializable
data class DeterministicDdiAlertDto(
    @SerialName("rule_id") val ruleId: String,
    @SerialName("salt_a") val saltA: String,
    @SerialName("salt_b") val saltB: String,
    val severity: String,
    val mechanism: String,
    @SerialName("clinical_effect") val clinicalEffect: String = "",
    val management: String,
    val sources: List<String> = emptyList(),
)

@Serializable
data class InteractionItemDto(
    @SerialName("salt_a") val saltA: String,
    @SerialName("salt_b") val saltB: String,
    val severity: String,
    val confidence: Double,
    val source: String,
    val mechanism: String,
    val management: String,
    @SerialName("ml_major_probability") val mlMajorProbability: Double? = null,
)

@Serializable
data class MonographCitationDto(
    @SerialName("chunk_id") val chunkId: String,
    @SerialName("set_id") val setId: String,
    @SerialName("canonical_salt") val canonicalSalt: String,
    @SerialName("section_key") val sectionKey: String = "",
    @SerialName("section_title") val sectionTitle: String,
    @SerialName("loinc_section_code") val loincSectionCode: String,
    val excerpt: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("lexical_score") val lexicalScore: Double? = null,
    @SerialName("dense_score") val denseScore: Double? = null,
    @SerialName("rrf_score") val rrfScore: Double? = null,
    @SerialName("rerank_score") val rerankScore: Double = 0.0,
)

@Serializable
data class ClinicalSummaryDto(
    val headline: String,
    @SerialName("patient_explanation") val patientExplanation: String,
    @SerialName("actionable_next_steps") val actionableNextSteps: List<String> = emptyList(),
    @SerialName("cited_chunk_ids") val citedChunkIds: List<String> = emptyList(),
    val abstained: Boolean = false,
    @SerialName("abstention_reason") val abstentionReason: String? = null,
    val disclaimer: String = "",
)

@Serializable
data class DeterministicSafetyEventDto(
    @SerialName("scan_id") val scanId: String,
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("deterministic_alerts") val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
)

@Serializable
data class InteractionsEventDto(
    @SerialName("scan_id") val scanId: String,
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    val interactions: List<InteractionItemDto> = emptyList(),
    @SerialName("ml_scored_pair_count") val mlScoredPairCount: Int = 0,
)

@Serializable
data class CitationsEventDto(
    @SerialName("scan_id") val scanId: String,
    val citations: List<MonographCitationDto> = emptyList(),
)

@Serializable
data class ClinicalSummaryEventDto(
    @SerialName("scan_id") val scanId: String,
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("citation_verified") val citationVerified: Boolean,
    @SerialName("blocked_citation_ids") val blockedCitationIds: List<String> = emptyList(),
    @SerialName("clinical_summary") val clinicalSummary: ClinicalSummaryDto,
    val citations: List<MonographCitationDto> = emptyList(),
)

@Serializable
data class ScanCompleteEventDto(
    @SerialName("scan_id") val scanId: String,
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("deterministic_alerts") val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    val interactions: List<InteractionItemDto> = emptyList(),
    @SerialName("clinical_summary") val clinicalSummary: ClinicalSummaryDto,
    val citations: List<MonographCitationDto> = emptyList(),
    @SerialName("citation_verified") val citationVerified: Boolean,
    @SerialName("blocked_citation_ids") val blockedCitationIds: List<String> = emptyList(),
    @SerialName("latency_ms") val latencyMs: Int = 0,
)

@Serializable
data class ResolvedSaltRefDto(
    @SerialName("canonical_salt") val canonicalSalt: String,
)

@Serializable
data class CabinetSnapshotItemDto(
    @SerialName("medication_id") val medicationId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("canonical_salt_keys") val canonicalSaltKeys: List<String> = emptyList(),
)

@Serializable
data class ScanLogResponseDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("input_mode") val inputMode: String,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
    @SerialName("edge_quality_metrics") val edgeQualityMetrics: EdgeQualityMetricsDto? = null,
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("resolved_salts") val resolvedSalts: List<ResolvedSaltRefDto> = emptyList(),
    @SerialName("cabinet_snapshot") val cabinetSnapshot: List<CabinetSnapshotItemDto> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("deterministic_alerts") val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    val interactions: List<InteractionItemDto> = emptyList(),
    @SerialName("clinical_summary") val clinicalSummary: ClinicalSummaryDto? = null,
    val citations: List<MonographCitationDto> = emptyList(),
    @SerialName("citation_verified") val citationVerified: Boolean = false,
    @SerialName("trace_id") val traceId: String? = null,
    @SerialName("latency_ms") val latencyMs: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class ScanLogListResponseDto(
    val items: List<ScanLogResponseDto> = emptyList(),
    val total: Int = 0,
    val limit: Int = 20,
    val offset: Int = 0,
)
