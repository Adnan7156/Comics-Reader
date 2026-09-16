package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScrollPositionDao {
    @Query("SELECT * FROM scroll_positions WHERE id = :id")
    suspend fun getScrollPosition(id: String): ScrollPosition?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveScrollPosition(scrollPosition: ScrollPosition)

    @Query("DELETE FROM scroll_positions WHERE id = :id")
    suspend fun deleteScrollPosition(id: String)
}
