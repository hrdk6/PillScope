package com.pillscope.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pillscope.app.data.local.dao.MedicationDao
import com.pillscope.app.data.local.dao.ScanReportDao
import com.pillscope.app.data.local.entity.CachedScanReportEntity
import com.pillscope.app.data.local.entity.MedicationEntity

@Database(
    entities = [
        MedicationEntity::class,
        CachedScanReportEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class PillScopeDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun scanReportDao(): ScanReportDao
}
