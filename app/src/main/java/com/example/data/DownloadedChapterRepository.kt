package com.example.data

import kotlinx.coroutines.flow.Flow

class DownloadedChapterRepository(private val downloadedChapterDao: DownloadedChapterDao) {
    val allChapters: Flow<List<DownloadedChapter>> = downloadedChapterDao.getAllChapters()

    suspend fun getChapterById(id: Long): DownloadedChapter? {
        return downloadedChapterDao.getChapterById(id)
    }

    suspend fun insert(chapter: DownloadedChapter): Long {
        return downloadedChapterDao.insertChapter(chapter)
    }

    suspend fun deleteById(id: Long) {
        downloadedChapterDao.deleteChapterById(id)
    }
}
