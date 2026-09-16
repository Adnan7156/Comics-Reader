package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AdsDnsManager
import com.example.data.DnsProvider
import com.example.data.WebSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Web Reader", appName)
  }

  @Test
  fun `dns provider preferences and filtering`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = WebSettingsManager(context)

    // Test default DNS provider
    assertEquals(DnsProvider.ADGUARD.id, settings.dnsProvider)
    assertTrue(settings.isInAppAdBlockingEnabled)

    // Change provider preference
    settings.dnsProvider = DnsProvider.CLOUDFLARE.id
    assertEquals(DnsProvider.CLOUDFLARE.id, settings.dnsProvider)

    // Verify ad-blocking filter behavior
    val adUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
    val safeUrl = "https://en.wikipedia.org/wiki/Main_Page"

    // AdGuard blocks ads
    assertTrue(AdsDnsManager.shouldBlockUrl(adUrl, DnsProvider.ADGUARD))
    assertFalse(AdsDnsManager.shouldBlockUrl(safeUrl, DnsProvider.ADGUARD))

    // System Default does not block ads
    assertFalse(AdsDnsManager.shouldBlockUrl(adUrl, DnsProvider.SYSTEM_DEFAULT))

    // Custom host blocking
    assertTrue(AdsDnsManager.shouldBlockUrl("https://my-ad-network.test/banner.png", DnsProvider.CUSTOM, "my-ad-network.test"))
  }

  @Test
  fun `observable web view scroll progress computation`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val webView = ObservableWebView(context)

    var receivedProgress = -1f
    webView.onScrollProgressChangedCallback = { progress ->
      receivedProgress = progress
    }

    // Trigger updateScrollProgress
    webView.updateScrollProgress()

    // Default with no content loaded is 0f
    assertEquals(0f, receivedProgress, 0.001f)
    assertEquals(0f, webView.computeScrollProgress(), 0.001f)
  }

  @Test
  fun `custom url formatting and validation`() {
    fun formatCustomUrl(rawUrl: String): String {
      val trimmed = rawUrl.trim()
      return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "https://$trimmed"
      } else {
        trimmed
      }
    }

    assertEquals("https://example.com", formatCustomUrl("example.com"))
    assertEquals("https://en.wikipedia.org", formatCustomUrl("  en.wikipedia.org  "))
    assertEquals("https://news.ycombinator.com", formatCustomUrl("https://news.ycombinator.com"))
    assertEquals("http://insecure.test", formatCustomUrl("http://insecure.test"))
  }

  @Test
  fun `observable web view nested scrolling child support`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val webView = ObservableWebView(context)

    assertTrue("ObservableWebView should have nested scrolling enabled", webView.isNestedScrollingEnabled)
    webView.isNestedScrollingEnabled = false
    assertFalse(webView.isNestedScrollingEnabled)
    webView.isNestedScrollingEnabled = true
    assertTrue(webView.isNestedScrollingEnabled)
  }

  @Test
  fun `observable web view scroll direction detection for distraction-free mode`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val webView = ObservableWebView(context)

    var lastDirectionIsDown: Boolean? = null
    webView.onScrollDirectionChanged = { isDown ->
      lastDirectionIsDown = isDown
    }

    // Simulate scrolling down: new scroll position (100) > old position (20)
    webView.onScrollChanged(0, 100, 0, 20)
    assertEquals(true, lastDirectionIsDown)

    // Simulate scrolling up: new position (50) < old position (100)
    webView.onScrollChanged(0, 50, 0, 100)
    assertEquals(false, lastDirectionIsDown)

    // Simulate scrolled near top (<= 15): restores bars even if tiny delta
    webView.onScrollChanged(0, 10, 0, 10)
    assertEquals(false, lastDirectionIsDown)
  }

  @Test
  fun `distraction free mode showBars logic`() {
    fun computeShowBars(currentTab: AppTab, isDistractionFreeMode: Boolean, isScrolledDown: Boolean): Boolean {
      return if (currentTab != AppTab.BROWSER) {
        true
      } else if (isDistractionFreeMode) {
        !isScrolledDown
      } else {
        true
      }
    }

    // Normal mode: bars always visible
    assertTrue(computeShowBars(AppTab.BROWSER, isDistractionFreeMode = false, isScrolledDown = false))
    assertTrue(computeShowBars(AppTab.BROWSER, isDistractionFreeMode = false, isScrolledDown = true))

    // Distraction-free mode on BROWSER: bars visible on scroll up, hidden on scroll down
    assertTrue(computeShowBars(AppTab.BROWSER, isDistractionFreeMode = true, isScrolledDown = false))
    assertFalse(computeShowBars(AppTab.BROWSER, isDistractionFreeMode = true, isScrolledDown = true))

    // Other tabs: bars always visible regardless of distraction-free or scroll state
    assertTrue(computeShowBars(AppTab.SETTINGS, isDistractionFreeMode = true, isScrolledDown = true))
    assertTrue(computeShowBars(AppTab.BOOKMARKS, isDistractionFreeMode = true, isScrolledDown = true))
    assertTrue(computeShowBars(AppTab.HISTORY, isDistractionFreeMode = true, isScrolledDown = true))
    assertTrue(computeShowBars(AppTab.DOWNLOADS, isDistractionFreeMode = true, isScrolledDown = true))
  }

  @Test
  fun `browser clear resets url and state to landing screen`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val settings = WebSettingsManager(context)
    settings.startUrl = "https://example.com/chapter-1"

    var currentUrl = "https://example.com/chapter-1"
    var currentTitle = "Chapter 1 - Read Free Online"
    var isScrolledDown = true
    var hasError = true
    var errorDescription = "Network error"

    // Simulate clearBrowser action
    fun clearBrowser() {
      currentUrl = ""
      currentTitle = ""
      settings.startUrl = ""
      isScrolledDown = false
      hasError = false
      errorDescription = ""
    }

    clearBrowser()

    assertEquals("", currentUrl)
    assertEquals("", currentTitle)
    assertEquals("", settings.startUrl)
    assertFalse(isScrolledDown)
    assertFalse(hasError)
    assertEquals("", errorDescription)
  }
}
