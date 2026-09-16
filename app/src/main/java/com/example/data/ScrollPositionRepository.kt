package com.example.data

class ScrollPositionRepository(private val scrollPositionDao: ScrollPositionDao) {
    suspend fun getScrollPosition(id: String): ScrollPosition? {
        return scrollPositionDao.getScrollPosition(id)
    }

    suspend fun saveScrollPosition(id: String, scrollY: Int, scrollIndex: Int = 0) {
        val scrollPosition = ScrollPosition(
            id = id,
            scrollY = scrollY,
            scrollIndex = scrollIndex,
            timestamp = System.currentTimeMillis()
        )
        scrollPositionDao.saveScrollPosition(scrollPosition)
    }

    suspend fun deleteScrollPosition(id: String) {
        scrollPositionDao.deleteScrollPosition(id)
    }
}
