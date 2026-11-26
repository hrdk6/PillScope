package com.pillscope.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
data class LogoutRequestDto(
    @SerialName("refresh_token") val refreshToken: String? = null,
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
data class AllergyEntryDto(
    val allergen: String,
    val reaction: String? = null,
    val severity: String? = null,
)

@Serializable
data class AllergyAlertDto(
    val allergen: String,
    @SerialName("canonical_salt") val canonicalSalt: String,
    @SerialName("match_type") val matchType: String,
    @SerialName("matched_class") val matchedClass: String? = null,
    val severity: String = "MAJOR",
    val reason: String,
)

@Serializable
data class ProfileCreateRequestDto(
    val name: String,
    val relationship: String = "other",
    @SerialName("allergies_json") val allergiesJson: List<JsonElement> = emptyList(),
    val notes: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class ProfileUpdateRequestDto(
    val name: String? = null,
    val relationship: String? = null,
    @SerialName("allergies_json") val allergiesJson: List<JsonElement>? = null,
    val notes: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
)

@Serializable
data class ProfileResponseDto(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    val name: String,
    val relationship: String = "self",
    @SerialName("allergies_json") val allergiesJson: List<JsonElement> = emptyList(),
    val notes: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun extractedAllergyEntries(): List<AllergyEntryDto> = allergiesJson.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
                AllergyEntryDto(allergen = it)
            }
            is JsonObject -> {
                val primary = (element["allergen"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["substance"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["name"] as? JsonPrimitive)?.contentOrNull
                val reaction = (element["reaction"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val severity = (element["severity"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                primary?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    AllergyEntryDto(allergen = it, reaction = reaction, severity = severity)
                }
            }
            else -> null
        }
    }

    fun extractedAllergyLabels(): List<String> = extractedAllergyEntries().map { it.allergen }
}

@Serializable
data class LifestyleTriggerDto(
    val type: String,
    val severity: String,
    @SerialName("short_note") val shortNote: String,
    @SerialName("source_section") val sourceSection: String,
)

@Serializable
data class MedicationRecallFlagDto(
    @SerialName("alert_label") val alertLabel: String = "US FDA Recall Alert",
    @SerialName("recall_id") val recallId: String,
    @SerialName("recall_number") val recallNumber: String,
    val classification: String,
    val status: String,
    @SerialName("recalling_firm") val recallingFirm: String? = null,
    @SerialName("reason_for_recall") val reasonForRecall: String,
    @SerialName("report_date") val reportDate: String? = null,
    @SerialName("match_basis") val matchBasis: String,
    @SerialName("matched_terms") val matchedTerms: List<String> = emptyList(),
    @SerialName("source_url") val sourceUrl: String,
)

@Serializable
data class ProfileRecallAlertItemDto(
    @SerialName("alert_label") val alertLabel: String = "US FDA Recall Alert",
    val jurisdiction: String = "US_FDA",
    @SerialName("recall_id") val recallId: String,
    @SerialName("recall_number") val recallNumber: String,
    @SerialName("event_id") val eventId: String? = null,
    val classification: String,
    val status: String,
    @SerialName("recalling_firm") val recallingFirm: String? = null,
    @SerialName("product_description") val productDescription: String,
    @SerialName("reason_for_recall") val reasonForRecall: String,
    @SerialName("distribution_pattern") val distributionPattern: String? = null,
    @SerialName("code_info") val codeInfo: String? = null,
    @SerialName("report_date") val reportDate: String? = null,
    @SerialName("recall_initiation_date") val recallInitiationDate: String? = null,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("matched_medication_id") val matchedMedicationId: String,
    @SerialName("matched_medication_name") val matchedMedicationName: String,
    @SerialName("match_basis") val matchBasis: String,
    @SerialName("matched_terms") val matchedTerms: List<String> = emptyList(),
)

@Serializable
data class ProfileRecallsResponseDto(
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_name") val profileName: String,
    @SerialName("alert_label") val alertLabel: String = "US FDA Recall Alert",
    @SerialName("has_active_recalls") val hasActiveRecalls: Boolean = false,
    @SerialName("total_matches") val totalMatches: Int = 0,
    val recalls: List<ProfileRecallAlertItemDto> = emptyList(),
)

@Serializable
data class ScheduledDoseItemDto(
    @SerialName("medication_id") val medicationId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("canonical_salt_keys") val canonicalSaltKeys: List<String> = emptyList(),
    @SerialName("dosage_schedule") val dosageSchedule: String? = null,
    val slot: String,
    @SerialName("recommended_time") val recommendedTime: String,
    @SerialName("dose_number") val doseNumber: Int = 1,
    @SerialName("total_daily_doses") val totalDailyDoses: Int = 1,
    @SerialName("frequency_note") val frequencyNote: String? = null,
    val rationale: List<String> = emptyList(),
    @SerialName("separation_notes") val separationNotes: List<String> = emptyList(),
)

@Serializable
data class ScheduleSlotGroupDto(
    val slot: String,
    @SerialName("recommended_time") val recommendedTime: String,
    val medications: List<ScheduledDoseItemDto> = emptyList(),
)

@Serializable
data class AppliedSeparationConstraintDto(
    @SerialName("medication_a_id") val medicationAId: String,
    @SerialName("medication_a_name") val medicationAName: String,
    @SerialName("salt_a") val saltA: String,
    @SerialName("medication_b_id") val medicationBId: String,
    @SerialName("medication_b_name") val medicationBName: String,
    @SerialName("salt_b") val saltB: String,
    @SerialName("min_separation_minutes") val minSeparationMinutes: Int,
    @SerialName("actual_separation_minutes") val actualSeparationMinutes: Int,
    val satisfied: Boolean,
    val reason: String,
)

@Serializable
data class UnresolvableScheduleClashDto(
    @SerialName("medication_a_id") val medicationAId: String,
    @SerialName("medication_a_name") val medicationAName: String,
    @SerialName("salt_a") val saltA: String,
    @SerialName("medication_b_id") val medicationBId: String,
    @SerialName("medication_b_name") val medicationBName: String,
    @SerialName("salt_b") val saltB: String,
    val severity: String = "MAJOR",
    @SerialName("clash_type") val clashType: String,
    val mechanism: String,
    val management: String,
    val reason: String,
)

@Serializable
data class ProfileScheduleResponseDto(
    @SerialName("profile_id") val profileId: String,
    @SerialName("profile_name") val profileName: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    val slots: List<ScheduleSlotGroupDto> = emptyList(),
    @SerialName("applied_separations") val appliedSeparations: List<AppliedSeparationConstraintDto> = emptyList(),
    @SerialName("unresolvable_clashes") val unresolvableClashes: List<UnresolvableScheduleClashDto> = emptyList(),
    val disclaimer: String = "",
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
    @SerialName("profile_id") val profileId: String? = null,
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
    @SerialName("generic_salts") val genericSalts: List<SaltComponentDto>? = null,
    @SerialName("dosage_schedule") val dosageSchedule: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
)

@Serializable
data class MedicationResponseDto(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("profile_id") val profileId: String = "",
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
    @SerialName("lifestyle_triggers") val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
    @SerialName("has_active_recall") val hasActiveRecall: Boolean = false,
    @SerialName("active_recalls") val activeRecalls: List<MedicationRecallFlagDto> = emptyList(),
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
)

@Serializable
data class MedicationCreateResponseDto(
    val medication: MedicationResponseDto,
    @SerialName("overall_risk") val overallRisk: String = "NONE",
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
)

@Serializable
data class DuplicateCheckRequestDto(
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("brand_name") val brandName: String? = null,
    @SerialName("confirmed_salts") val confirmedSalts: List<String>? = null,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
)

@Serializable
data class DuplicateCheckResponseDto(
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String = "NONE",
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
)

@Serializable
data class EdgeQualityMetricsDto(
    @SerialName("blur_score") val blurScore: Float,
    @SerialName("glare_ratio") val glareRatio: Float,
    @SerialName("crop_confidence") val cropConfidence: Float,
)

@Serializable
data class ScanAnalyzeRequestDto(
    @SerialName("profile_id") val profileId: String? = null,
    val persist: Boolean = true,
    @SerialName("input_mode") val inputMode: String = "CAMERA_OCR",
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("confirmed_salts") val confirmedSalts: List<String>? = null,
    @SerialName("raw_ocr_text") val rawOcrText: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val language: String = "en",
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
    val language: String = "en",
    val disclaimer: String = "",
)

@Serializable
data class DeterministicSafetyEventDto(
    @SerialName("scan_id") val scanId: String,
    @SerialName("profile_id") val profileId: String? = null,
    val persisted: Boolean = true,
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
    @SerialName("deterministic_alerts") val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
    @SerialName("active_recalls") val activeRecalls: List<MedicationRecallFlagDto> = emptyList(),
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
    @SerialName("lifestyle_triggers") val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
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
    @SerialName("profile_id") val profileId: String? = null,
    val persisted: Boolean = true,
    @SerialName("submitted_query") val submittedQuery: String,
    @SerialName("resolved_salts") val resolvedSalts: List<String> = emptyList(),
    @SerialName("overall_risk") val overallRisk: String,
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean,
    @SerialName("deterministic_alerts") val deterministicAlerts: List<DeterministicDdiAlertDto> = emptyList(),
    @SerialName("duplicate_salts") val duplicateSalts: List<DuplicateSaltAlertDto> = emptyList(),
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
    @SerialName("lifestyle_triggers") val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
    @SerialName("active_recalls") val activeRecalls: List<MedicationRecallFlagDto> = emptyList(),
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
    @SerialName("profile_id") val profileId: String = "",
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
    @SerialName("allergy_alerts") val allergyAlerts: List<AllergyAlertDto> = emptyList(),
    @SerialName("lifestyle_triggers") val lifestyleTriggers: List<LifestyleTriggerDto> = emptyList(),
    @SerialName("active_recalls") val activeRecalls: List<MedicationRecallFlagDto> = emptyList(),
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

@Serializable
data class SupportedLanguageInfoDto(
    val code: String,
    val bcp47: String,
    @SerialName("english_name") val englishName: String,
    @SerialName("native_name") val nativeName: String,
    @SerialName("script_name") val scriptName: String = "",
)

@Serializable
data class SupportedLanguagesResponseDto(
    @SerialName("default_language") val defaultLanguage: String = "en",
    val languages: List<SupportedLanguageInfoDto> = emptyList(),
)

@Serializable
data class AssistantTurnDto(
    val role: String,
    val text: String,
)

@Serializable
data class AssistantAskRequestDto(
    val question: String,
    val language: String = "en",
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("scan_id") val scanId: String? = null,
    @SerialName("context_medicine_name") val contextMedicineName: String? = null,
    @SerialName("context_salts") val contextSalts: List<String> = emptyList(),
    @SerialName("conversation_history") val conversationHistory: List<AssistantTurnDto> = emptyList(),
)

@Serializable
data class AssistantAskResponseDto(
    @SerialName("answer_text") val answerText: String,
    @SerialName("spoken_text") val spokenText: String,
    val language: String = "en",
    @SerialName("bcp47_locale") val bcp47Locale: String = "en-IN",
    @SerialName("overall_risk") val overallRisk: String = "NONE",
    @SerialName("safety_banner_locked") val safetyBannerLocked: Boolean = false,
    @SerialName("referenced_salts") val referencedSalts: List<String> = emptyList(),
    @SerialName("suggested_followups") val suggestedFollowups: List<String> = emptyList(),
    val citations: List<MonographCitationDto> = emptyList(),
    val disclaimer: String = "",
    val provider: String = "deterministic_grounded",
)

@Serializable
data class AssistantTtsRequestDto(
    val text: String,
    val language: String = "en",
    val voice: String? = null,
)
