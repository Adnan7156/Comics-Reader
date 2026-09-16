package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflinePageDao {
    @Query("SELECT * FROM offline_pages ORDER BY timestamp DESC")
    fun getAllOfflinePages(): Flow<List<OfflinePage>>

    @Query("SELECT * FROM offline_pages WHERE url = :url LIMIT 1")
    fun getOfflinePageByUrl(url: String): Flow<OfflinePage?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: OfflinePage): Long

    @Query("DELETE FROM offline_pages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM offline_pages WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}
