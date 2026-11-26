package com.pillscope.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {
    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE is_active = 1 AND sync_status != '${SyncStatus.PENDING_DELETE}' " +
            "ORDER BY updated_at_epoch_ms DESC",
    )
    fun observeActiveMedications(): Flow<List<MedicationEntity>>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE sync_status != '${SyncStatus.PENDING_DELETE}' " +
            "ORDER BY is_active DESC, updated_at_epoch_ms DESC",
    )
    fun observeAllVisibleMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM cabinet_medications WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MedicationEntity?

    @Query("SELECT * FROM cabinet_medications WHERE sync_status != '${SyncStatus.SYNCED}'")
    suspend fun getPendingSync(): List<MedicationEntity>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE is_active = 1 AND sync_status != '${SyncStatus.PENDING_DELETE}'",
    )
    suspend fun getActiveSnapshot(): List<MedicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MedicationEntity>)

    @Query("DELETE FROM cabinet_medications WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cabinet_medications WHERE sync_status = '${SyncStatus.SYNCED}'")
    suspend fun clearSynced()
}

@Dao
interface ScanReportDao {
    @Query("SELECT * FROM cached_scan_reports ORDER BY created_at_epoch_ms DESC LIMIT :limit")
    fun observeRecentReports(limit: Int = 30): Flow<List<CachedScanReportEntity>>

    @Query("SELECT * FROM cached_scan_reports WHERE scan_id = :scanId LIMIT 1")
    suspend fun getByScanId(scanId: String): CachedScanReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedScanReportEntity)
}
