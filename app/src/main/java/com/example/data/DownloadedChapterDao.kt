package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadedChapterDao {
    @Query("SELECT * FROM downloaded_chapters ORDER BY timestamp DESC")
    fun getAllChapters(): Flow<List<DownloadedChapter>>

    @Query("SELECT * FROM downloaded_chapters WHERE id = :id")
    suspend fun getChapterById(id: Long): DownloadedChapter?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapter(chapter: DownloadedChapter): Long

    @Query("DELETE FROM downloaded_chapters WHERE id = :id")
    suspend fun deleteChapterById(id: Long)
}
