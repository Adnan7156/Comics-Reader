package com.example.data

import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {
    val allHistory: Flow<List<HistoryItem>> = historyDao.getAllHistory()

    suspend fun insert(historyItem: HistoryItem) {
        historyDao.insertHistory(historyItem)
    }

    suspend fun delete(historyItem: HistoryItem) {
        historyDao.deleteHistory(historyItem)
    }

    suspend fun deleteByUrl(url: String) {
        historyDao.deleteByUrl(url)
    }

    suspend fun clearAll() {
        historyDao.clearAllHistory()
    }
}
