package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_items ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(historyItem: HistoryItem)

    @Delete
    suspend fun deleteHistory(historyItem: HistoryItem)

    @Query("DELETE FROM history_items WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM history_items")
    suspend fun clearAllHistory()
}
