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

@Entity(tableName = "cabinet_medications")
data class MedicationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "brand_mapping_id") val brandMappingId: String? = null,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "generic_salts_json") val genericSaltsJson: String,
    @ColumnInfo(name = "canonical_salt_keys_csv") val canonicalSaltKeysCsv: String,
    @ColumnInfo(name = "dosage_schedule") val dosageSchedule: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.SYNCED,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "cached_scan_reports")
data class CachedScanReportEntity(
    @PrimaryKey @ColumnInfo(name = "scan_id") val scanId: String,
    @ColumnInfo(name = "submitted_query") val submittedQuery: String,
    @ColumnInfo(name = "resolved_salts_json") val resolvedSaltsJson: String,
    @ColumnInfo(name = "overall_risk") val overallRisk: String,
    @ColumnInfo(name = "safety_banner_locked") val safetyBannerLocked: Boolean,
    @ColumnInfo(name = "duplicate_salts_json") val duplicateSaltsJson: String,
    @ColumnInfo(name = "deterministic_alerts_json") val deterministicAlertsJson: String,
    @ColumnInfo(name = "interactions_json") val interactionsJson: String,
    @ColumnInfo(name = "clinical_summary_json") val clinicalSummaryJson: String,
    @ColumnInfo(name = "citations_json") val citationsJson: String,
    @ColumnInfo(name = "citation_verified") val citationVerified: Boolean,
    @ColumnInfo(name = "latency_ms") val latencyMs: Int,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)
