package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "offline_pages")
data class OfflinePage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val localFileName: String,
    val fileSize: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
