package com.pillscope.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pillscope.app.data.local.entity.CachedDoseScheduleEntity
import com.pillscope.app.data.local.entity.CachedLifestyleTriggerEntity
import com.pillscope.app.data.local.entity.CachedRecallAlertEntity
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.local.entity.MedicationEntity
import com.pillscope.app.data.local.entity.ProfileEntity
import com.pillscope.app.data.local.entity.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY is_default DESC, updated_at_epoch_ms ASC")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY is_default DESC, updated_at_epoch_ms ASC")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE is_default = 1 LIMIT 1")
    suspend fun getDefaultProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM profiles")
    suspend fun clearAll()
}

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
            "WHERE profile_id = :profileId AND is_active = 1 " +
            "AND sync_status != '${SyncStatus.PENDING_DELETE}' " +
            "ORDER BY updated_at_epoch_ms DESC",
    )
    fun observeActiveMedicationsForProfile(profileId: String): Flow<List<MedicationEntity>>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE sync_status != '${SyncStatus.PENDING_DELETE}' " +
            "ORDER BY is_active DESC, updated_at_epoch_ms DESC",
    )
    fun observeAllVisibleMedications(): Flow<List<MedicationEntity>>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE profile_id = :profileId AND sync_status != '${SyncStatus.PENDING_DELETE}' " +
            "ORDER BY is_active DESC, updated_at_epoch_ms DESC",
    )
    fun observeVisibleMedicationsForProfile(profileId: String): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM cabinet_medications WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MedicationEntity?

    @Query("SELECT * FROM cabinet_medications WHERE sync_status != '${SyncStatus.SYNCED}'")
    suspend fun getPendingSync(): List<MedicationEntity>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE is_active = 1 AND sync_status != '${SyncStatus.PENDING_DELETE}'",
    )
    suspend fun getActiveSnapshot(): List<MedicationEntity>

    @Query(
        "SELECT * FROM cabinet_medications " +
            "WHERE profile_id = :profileId AND is_active = 1 " +
            "AND sync_status != '${SyncStatus.PENDING_DELETE}'",
    )
    suspend fun getActiveSnapshotForProfile(profileId: String): List<MedicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MedicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MedicationEntity>)

    @Query("DELETE FROM cabinet_medications WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cabinet_medications WHERE sync_status = '${SyncStatus.SYNCED}'")
    suspend fun clearSynced()

    @Query(
        "DELETE FROM cabinet_medications " +
            "WHERE profile_id = :profileId AND sync_status = '${SyncStatus.SYNCED}'",
    )
    suspend fun clearSyncedForProfile(profileId: String)
}

@Dao
interface ScanReportDao {
    @Query("SELECT * FROM cached_scan_reports ORDER BY created_at_epoch_ms DESC LIMIT :limit")
    fun observeRecentReports(limit: Int = 30): Flow<List<CachedScanReportEntity>>

    @Query(
        "SELECT * FROM cached_scan_reports " +
            "WHERE profile_id = :profileId ORDER BY created_at_epoch_ms DESC LIMIT :limit",
    )
    fun observeRecentReportsForProfile(
        profileId: String,
        limit: Int = 30,
    ): Flow<List<CachedScanReportEntity>>

    @Query("SELECT * FROM cached_scan_reports WHERE scan_id = :scanId LIMIT 1")
    suspend fun getByScanId(scanId: String): CachedScanReportEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedScanReportEntity)
}

@Dao
interface LifestyleTriggerDao {
    @Query("SELECT * FROM lifestyle_triggers WHERE canonical_salt IN (:salts)")
    suspend fun getForSalts(salts: List<String>): List<CachedLifestyleTriggerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedLifestyleTriggerEntity>)
}

@Dao
interface DoseScheduleDao {
    @Query("SELECT * FROM dose_schedule WHERE profile_id = :profileId LIMIT 1")
    fun observeForProfile(profileId: String): Flow<CachedDoseScheduleEntity?>

    @Query("SELECT * FROM dose_schedule WHERE profile_id = :profileId LIMIT 1")
    suspend fun getForProfile(profileId: String): CachedDoseScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedDoseScheduleEntity)
}

@Dao
interface RecallAlertDao {
    @Query(
        "SELECT * FROM recall_alerts WHERE profile_id = :profileId " +
            "ORDER BY classification ASC, updated_at_epoch_ms DESC",
    )
    fun observeForProfile(profileId: String): Flow<List<CachedRecallAlertEntity>>

    @Query("DELETE FROM recall_alerts WHERE profile_id = :profileId")
    suspend fun clearForProfile(profileId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CachedRecallAlertEntity>)
}
