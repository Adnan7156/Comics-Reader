package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scroll_positions")
data class ScrollPosition(
    @PrimaryKey val id: String, // Can be WebView URL or offline localDirName/chapterId
    val scrollY: Int,          // For WebView or LazyColumn offset
    val scrollIndex: Int = 0,  // For LazyColumn index (offline reader)
    val timestamp: Long = System.currentTimeMillis()
)
