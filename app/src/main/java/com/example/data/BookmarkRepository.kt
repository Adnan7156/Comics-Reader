package com.example.data

import kotlinx.coroutines.flow.Flow

class BookmarkRepository(private val bookmarkDao: BookmarkDao) {
    val allBookmarks: Flow<List<Bookmark>> = bookmarkDao.getAllBookmarks()

    suspend fun insert(bookmark: Bookmark) {
        bookmarkDao.insertBookmark(bookmark)
    }

    suspend fun delete(bookmark: Bookmark) {
        bookmarkDao.deleteBookmark(bookmark)
    }

    suspend fun deleteByUrl(url: String) {
        bookmarkDao.deleteByUrl(url)
    }

    fun isBookmarked(url: String): Flow<Boolean> {
        return bookmarkDao.isBookmarked(url)
    }
}
