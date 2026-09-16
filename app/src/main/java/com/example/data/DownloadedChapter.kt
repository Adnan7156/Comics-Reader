package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_chapters")
data class DownloadedChapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val pageCount: Int,
    val localDirName: String,
    val imageFiles: String, // Comma-separated list of local filenames, e.g. "page_0.jpg,page_1.jpg"
    val timestamp: Long = System.currentTimeMillis()
)
