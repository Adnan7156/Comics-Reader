package com.example.data

import android.content.Context
import android.content.SharedPreferences

class WebSettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("my_web_app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_START_URL = "start_url"
        private const val KEY_JS_ENABLED = "js_enabled"
        private const val KEY_FORCE_DARK_ALLOWED = "force_dark_allowed"
        private const val KEY_DISTRACTION_FREE_MODE = "distraction_free_mode"
        private const val KEY_DNS_PROVIDER = "dns_provider"
        private const val KEY_CUSTOM_DNS_HOST = "custom_dns_host"
        private const val KEY_IN_APP_AD_BLOCK = "in_app_ad_block"
        private const val DEFAULT_URL = ""
    }

    var dnsProvider: String
        get() = prefs.getString(KEY_DNS_PROVIDER, DnsProvider.ADGUARD.id) ?: DnsProvider.ADGUARD.id
        set(value) {
            prefs.edit().putString(KEY_DNS_PROVIDER, value).apply()
        }

    var customDnsHost: String
        get() = prefs.getString(KEY_CUSTOM_DNS_HOST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_CUSTOM_DNS_HOST, value.trim()).apply()
        }

    var isInAppAdBlockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_IN_APP_AD_BLOCK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_IN_APP_AD_BLOCK, value).apply()
        }

    var startUrl: String
        get() = prefs.getString(KEY_START_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().putString(KEY_START_URL, "").apply()
                return
            }
            // ensure it starts with http or https
            val finalUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                "https://$trimmed"
            } else {
                trimmed
            }
            prefs.edit().putString(KEY_START_URL, finalUrl).apply()
        }

    var isJsEnabled: Boolean
        get() = prefs.getBoolean(KEY_JS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JS_ENABLED, value).apply()
        }

    var isForceDarkAllowed: Boolean
        get() = prefs.getBoolean(KEY_FORCE_DARK_ALLOWED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FORCE_DARK_ALLOWED, value).apply()
        }

    var isDistractionFreeMode: Boolean
        get() = prefs.getBoolean(KEY_DISTRACTION_FREE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DISTRACTION_FREE_MODE, value).apply()
        }
}
