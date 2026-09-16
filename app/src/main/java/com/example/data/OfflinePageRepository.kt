package com.example.data

import kotlinx.coroutines.flow.Flow

class OfflinePageRepository(private val dao: OfflinePageDao) {
    val allPages: Flow<List<OfflinePage>> = dao.getAllOfflinePages()

    fun getPageByUrl(url: String): Flow<OfflinePage?> = dao.getOfflinePageByUrl(url)

    suspend fun insert(page: OfflinePage): Long = dao.insert(page)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteByUrl(url: String) = dao.deleteByUrl(url)
}
