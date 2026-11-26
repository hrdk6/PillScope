package com.pillscope.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

object SyncStatus {
    const val SYNCED = "SYNCED"
    const val PENDING_CREATE = "PENDING_CREATE"
    const val PENDING_UPDATE = "PENDING_UPDATE"
    const val PENDING_DELETE = "PENDING_DELETE"
}

object DefaultProfileIds {
    const val LOCAL_DEFAULT = "default-local-profile"
}

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String = "",
    val name: String,
    val relationship: String = "self",
    @ColumnInfo(name = "allergies_json") val allergiesJson: String = "[]",
    val notes: String? = null,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "cabinet_medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String = DefaultProfileIds.LOCAL_DEFAULT,
    @ColumnInfo(name = "brand_mapping_id") val brandMappingId: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "generic_salts_json") val genericSaltsJson: String,
    @ColumnInfo(name = "canonical_salt_keys_csv") val canonicalSaltKeysCsv: String,
    @ColumnInfo(name = "dosage_schedule") val dosageSchedule: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.SYNCED,
    @ColumnInfo(name = "lifestyle_triggers_json") val lifestyleTriggersJson: String = "[]",
    @ColumnInfo(name = "has_active_recall") val hasActiveRecall: Boolean = false,
    @ColumnInfo(name = "active_recalls_json") val activeRecallsJson: String = "[]",
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "cached_scan_reports")
data class CachedScanReportEntity(
    @PrimaryKey @ColumnInfo(name = "scan_id") val scanId: String,
    @ColumnInfo(name = "profile_id") val profileId: String = DefaultProfileIds.LOCAL_DEFAULT,
    val persisted: Boolean = true,
    @ColumnInfo(name = "submitted_query") val submittedQuery: String,
    @ColumnInfo(name = "resolved_salts_json") val resolvedSaltsJson: String,
    @ColumnInfo(name = "overall_risk") val overallRisk: String,
    @ColumnInfo(name = "safety_banner_locked") val safetyBannerLocked: Boolean,
    @ColumnInfo(name = "duplicate_salts_json") val duplicateSaltsJson: String,
    @ColumnInfo(name = "allergy_alerts_json") val allergyAlertsJson: String = "[]",
    @ColumnInfo(name = "lifestyle_triggers_json") val lifestyleTriggersJson: String = "[]",
    @ColumnInfo(name = "deterministic_alerts_json") val deterministicAlertsJson: String,
    @ColumnInfo(name = "interactions_json") val interactionsJson: String,
    @ColumnInfo(name = "clinical_summary_json") val clinicalSummaryJson: String,
    @ColumnInfo(name = "citations_json") val citationsJson: String,
    @ColumnInfo(name = "citation_verified") val citationVerified: Boolean,
    @ColumnInfo(name = "latency_ms") val latencyMs: Int,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(tableName = "lifestyle_triggers")
data class CachedLifestyleTriggerEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "canonical_salt") val canonicalSalt: String,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    val severity: String,
    @ColumnInfo(name = "short_note") val shortNote: String,
    @ColumnInfo(name = "source_section") val sourceSection: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "dose_schedule")
data class CachedDoseScheduleEntity(
    @PrimaryKey @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "profile_name") val profileName: String,
    @ColumnInfo(name = "safety_banner_locked") val safetyBannerLocked: Boolean,
    @ColumnInfo(name = "schedule_json") val scheduleJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "recall_alerts")
data class CachedRecallAlertEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "recall_id") val recallId: String,
    @ColumnInfo(name = "recall_number") val recallNumber: String,
    @ColumnInfo(name = "alert_label") val alertLabel: String,
    val classification: String,
    val status: String,
    @ColumnInfo(name = "recalling_firm") val recallingFirm: String? = null,
    @ColumnInfo(name = "product_description") val productDescription: String,
    @ColumnInfo(name = "reason_for_recall") val reasonForRecall: String,
    @ColumnInfo(name = "report_date") val reportDate: String? = null,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "matched_medication_id") val matchedMedicationId: String,
    @ColumnInfo(name = "matched_medication_name") val matchedMedicationName: String,
    @ColumnInfo(name = "match_basis") val matchBasis: String,
    @ColumnInfo(name = "matched_terms_json") val matchedTermsJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)
