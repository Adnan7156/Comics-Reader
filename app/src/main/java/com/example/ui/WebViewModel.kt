package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.Bookmark
import com.example.data.BookmarkRepository
import com.example.data.HistoryItem
import com.example.data.HistoryRepository
import com.example.data.DownloadedChapter
import com.example.data.DownloadedChapterRepository
import com.example.data.OfflinePage
import com.example.data.OfflinePageRepository
import com.example.data.ScrollPosition
import com.example.data.ScrollPositionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class WebViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: BookmarkRepository
    private val historyRepository: HistoryRepository
    private val downloadRepository: DownloadedChapterRepository
    private val scrollRepository: ScrollPositionRepository
    private val offlinePageRepository: OfflinePageRepository

    val bookmarks: StateFlow<List<Bookmark>>
    val history: StateFlow<List<HistoryItem>>
    val downloadedChapters: StateFlow<List<DownloadedChapter>>
    val offlinePages: StateFlow<List<OfflinePage>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = BookmarkRepository(database.bookmarkDao())
        historyRepository = HistoryRepository(database.historyDao())
        downloadRepository = DownloadedChapterRepository(database.downloadedChapterDao())
        scrollRepository = ScrollPositionRepository(database.scrollPositionDao())
        offlinePageRepository = OfflinePageRepository(database.offlinePageDao())
        
        bookmarks = repository.allBookmarks.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        history = historyRepository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        downloadedChapters = downloadRepository.allChapters.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        offlinePages = offlinePageRepository.allPages.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun isBookmarked(url: String): Flow<Boolean> {
        return repository.isBookmarked(url)
    }

    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch {
            val isCurrentlyBookmarked = repository.isBookmarked(url).first()
            if (isCurrentlyBookmarked) {
                repository.deleteByUrl(url)
            } else {
                repository.insert(Bookmark(url = url, title = title))
            }
        }
    }

    fun addBookmark(url: String, title: String) {
        viewModelScope.launch {
            repository.insert(Bookmark(url = url, title = title))
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            repository.deleteByUrl(url)
        }
    }

    fun addHistory(url: String, title: String) {
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        viewModelScope.launch {
            historyRepository.insert(HistoryItem(url = url, title = title))
        }
    }

    fun removeHistory(url: String) {
        viewModelScope.launch {
            historyRepository.deleteByUrl(url)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
        }
    }

    fun downloadChapter(
        title: String,
        sourceUrl: String,
        imageUrlList: List<String>,
        onProgress: (String) -> Unit = {},
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (imageUrlList.isEmpty()) {
                    launch(Dispatchers.Main) { onComplete(false, "No images found on this page") }
                    return@launch
                }

                // Create a unique folder for this chapter
                val timestamp = System.currentTimeMillis()
                val dirName = "chapter_$timestamp"
                val context = getApplication<Application>()
                val downloadDir = File(context.filesDir, "downloads/$dirName")
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs()
                }

                val savedFiles = mutableListOf<String>()
                imageUrlList.forEachIndexed { index, imageUrl ->
                    launch(Dispatchers.Main) {
                        onProgress("Downloading page ${index + 1} of ${imageUrlList.size}...")
                    }

                    // Download image file
                    val fileName = "page_$index" + getFileExtension(imageUrl)
                    val file = File(downloadDir, fileName)
                    val success = downloadImageToFile(imageUrl, file)
                    if (success) {
                        savedFiles.add(fileName)
                    }
                }

                if (savedFiles.isEmpty()) {
                    launch(Dispatchers.Main) { onComplete(false, "Failed to download any images") }
                    return@launch
                }

                // Save details to database
                val chapter = DownloadedChapter(
                    title = title,
                    url = sourceUrl,
                    pageCount = savedFiles.size,
                    localDirName = dirName,
                    imageFiles = savedFiles.joinToString(","),
                    timestamp = timestamp
                )
                downloadRepository.insert(chapter)

                launch(Dispatchers.Main) {
                    onComplete(true, "Successfully downloaded ${savedFiles.size} pages!")
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    onComplete(false, "Error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun deleteChapter(chapter: DownloadedChapter) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val downloadDir = File(context.filesDir, "downloads/${chapter.localDirName}")
                if (downloadDir.exists()) {
                    downloadDir.deleteRecursively()
                }
                downloadRepository.deleteById(chapter.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getFileExtension(url: String): String {
        if (url.startsWith("data:image/")) {
            val type = url.substringAfter("data:image/").substringBefore(";")
            return when (type) {
                "png" -> ".png"
                "webp" -> ".webp"
                "gif" -> ".gif"
                else -> ".jpg"
            }
        }
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        return when {
            cleanUrl.endsWith(".png", ignoreCase = true) -> ".png"
            cleanUrl.endsWith(".webp", ignoreCase = true) -> ".webp"
            cleanUrl.endsWith(".gif", ignoreCase = true) -> ".gif"
            else -> ".jpg"
        }
    }

    private fun downloadImageToFile(urlString: String, file: File): Boolean {
        return try {
            if (urlString.startsWith("data:image/")) {
                // Decode base64 image
                val base64Data = urlString.substringAfter("base64,")
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                file.writeBytes(bytes)
                true
            } else {
                val url = java.net.URL(urlString)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun getScrollPosition(id: String): ScrollPosition? {
        return scrollRepository.getScrollPosition(id)
    }

    private val scrollJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun saveScrollPosition(id: String, scrollY: Int, scrollIndex: Int = 0) {
        if (id.isBlank() || id.startsWith("about:") || id.startsWith("data:")) return
        scrollJobs[id]?.cancel()
        scrollJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(200) // 200ms debounce
            scrollRepository.saveScrollPosition(id, scrollY, scrollIndex)
        }
    }

    fun saveOfflinePage(
        title: String,
        url: String,
        htmlContent: String,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (htmlContent.isBlank()) {
                    launch(Dispatchers.Main) {
                        onComplete(false, "Webpage content is empty")
                    }
                    return@launch
                }

                val context = getApplication<Application>()
                val offlineDir = File(context.filesDir, "offline_pages")
                if (!offlineDir.exists()) {
                    offlineDir.mkdirs()
                }

                val timestamp = System.currentTimeMillis()
                val fileName = "page_${timestamp}.html"
                val file = File(offlineDir, fileName)
                file.writeText(htmlContent, Charsets.UTF_8)
                val fileSize = file.length()

                val offlinePage = OfflinePage(
                    title = title.ifBlank { url.ifBlank { "Offline Page" } },
                    url = url,
                    localFileName = fileName,
                    fileSize = fileSize,
                    timestamp = timestamp
                )
                offlinePageRepository.insert(offlinePage)

                val formattedSize = when {
                    fileSize >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", fileSize / (1024.0 * 1024.0))
                    fileSize >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", fileSize / 1024.0)
                    else -> "$fileSize bytes"
                }

                launch(Dispatchers.Main) {
                    onComplete(true, "Page saved for offline reading! ($formattedSize)")
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    onComplete(false, "Failed to save offline page: ${e.localizedMessage}")
                }
            }
        }
    }

    fun deleteOfflinePage(page: OfflinePage, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val file = File(context.filesDir, "offline_pages/${page.localFileName}")
                if (file.exists()) {
                    file.delete()
                }
                offlinePageRepository.deleteById(page.id)
                launch(Dispatchers.Main) {
                    onComplete(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                launch(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    fun loadOfflineHtml(page: OfflinePage): String {
        return try {
            val context = getApplication<Application>()
            val file = File(context.filesDir, "offline_pages/${page.localFileName}")
            if (file.exists()) {
                file.readText(Charsets.UTF_8)
            } else {
                "<html><body><h2>Saved page file not found</h2><p>The local file might have been removed.</p></body></html>"
            }
        } catch (e: Exception) {
            "<html><body><h2>Error loading offline page</h2><p>${e.localizedMessage}</p></body></html>"
        }
    }
}
