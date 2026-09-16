package com.example.data

import android.net.Uri

enum class DnsProvider(
    val id: String,
    val displayName: String,
    val hostName: String,
    val ipAddress: String,
    val blocksAds: Boolean,
    val description: String
) {
    ADGUARD(
        id = "adguard",
        displayName = "AdGuard DNS (Blocks Ads & Trackers)",
        hostName = "dns.adguard-dns.com",
        ipAddress = "94.140.14.14",
        blocksAds = true,
        description = "Blocks video ads, popups, tracking scripts, and banner networks."
    ),
    ADGUARD_FAMILY(
        id = "adguard_family",
        displayName = "AdGuard Family (Ads & Adult Block)",
        hostName = "family.adguard-dns.com",
        ipAddress = "94.140.14.15",
        blocksAds = true,
        description = "Blocks ads, trackers, adult sites, and enforces safe search."
    ),
    NEXTDNS(
        id = "nextdns",
        displayName = "NextDNS (Privacy & Ad Block)",
        hostName = "dns.nextdns.io",
        ipAddress = "45.90.28.0",
        blocksAds = true,
        description = "Modern cloud DNS with tracker prevention and ad blocking."
    ),
    CLOUDFLARE(
        id = "cloudflare",
        displayName = "Cloudflare (1.1.1.1 - Fast & Private)",
        hostName = "one.one.one.one",
        ipAddress = "1.1.1.1",
        blocksAds = false,
        description = "High-speed privacy-focused DNS. Does not block ads."
    ),
    CLOUDFLARE_SECURITY(
        id = "cloudflare_security",
        displayName = "Cloudflare Security (1.1.1.2)",
        hostName = "security.cloudflare-dns.com",
        ipAddress = "1.1.1.2",
        blocksAds = false,
        description = "High-speed DNS with malware and phishing protection."
    ),
    GOOGLE(
        id = "google",
        displayName = "Google Public DNS (8.8.8.8)",
        hostName = "dns.google",
        ipAddress = "8.8.8.8",
        blocksAds = false,
        description = "Reliable standard global DNS without content filtering."
    ),
    QUAD9(
        id = "quad9",
        displayName = "Quad9 (Threat & Malware Block)",
        hostName = "dns.quad9.net",
        ipAddress = "9.9.9.9",
        blocksAds = false,
        description = "Blocks malicious domains, botnets, and phishing scams."
    ),
    CUSTOM(
        id = "custom",
        displayName = "Custom DNS / AdBlock Host",
        hostName = "",
        ipAddress = "",
        blocksAds = true,
        description = "Use your personal Private DNS hostname or custom ad filter."
    ),
    SYSTEM_DEFAULT(
        id = "system",
        displayName = "System Default (Off)",
        hostName = "",
        ipAddress = "",
        blocksAds = false,
        description = "Standard device DNS without in-app ad blocking."
    );

    companion object {
        fun fromId(id: String): DnsProvider {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: ADGUARD
        }
    }
}

object AdsDnsManager {
    // Known ad & tracking server domains
    private val AD_DOMAINS = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "popads.net",
        "popcash.net",
        "adroll.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "amazon-adsystem.com",
        "rubiconproject.com",
        "smartadserver.com",
        "pubmatic.com",
        "openx.net",
        "adcolony.com",
        "vungle.com",
        "applovin.com",
        "inmobi.com",
        "chartboost.com",
        "scorecardresearch.com",
        "zedo.com",
        "media.net",
        "ad-delivery.net",
        "adskeeper.com",
        "mgid.com",
        "propellerads.com",
        "exoclick.com",
        "trafficjunky.com",
        "bidswitch.net",
        "casalemedia.com",
        "advertising.com",
        "moatads.com",
        "adform.net",
        "flashtalking.com",
        "revcontent.com",
        "carbonads.com",
        "serving-sys.com",
        "yieldmo.com",
        "triplelift.com",
        "contextweb.com",
        "adservice.com",
        "adtechus.com",
        "adlightning.com",
        "adtrue.com",
        "adversal.com",
        "affiliate-tracking.com"
    )

    private val AD_HOST_PREFIXES = listOf(
        "ad.",
        "ads.",
        "adserver.",
        "pagead.",
        "pagead2.",
        "telemetry.",
        "adclick.",
        "banner."
    )

    private val AD_PATH_KEYWORDS = listOf(
        "/pagead/",
        "/gpt/pubads",
        "/ads/ad_",
        "/adserver/",
        "/ads.js",
        "/prebid.js"
    )

    private val THREAT_DOMAINS = setOf(
        "malware-traffic-analysis.net",
        "phishing-test.com"
    )

    /**
     * Checks whether an incoming HTTP/HTTPS request matches the active DNS/Ad filtering rules.
     */
    fun shouldBlockUrl(url: String, provider: DnsProvider, customHost: String = ""): Boolean {
        if (provider == DnsProvider.SYSTEM_DEFAULT) {
            return false
        }

        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""

        // Custom DNS host matching
        if (provider == DnsProvider.CUSTOM && customHost.isNotBlank()) {
            val cleanCustom = customHost.trim().lowercase()
            if (host.contains(cleanCustom)) {
                return true
            }
        }

        // Threat protection (Quad9, Cloudflare Security, AdGuard)
        if (provider == DnsProvider.QUAD9 || provider == DnsProvider.CLOUDFLARE_SECURITY) {
            if (THREAT_DOMAINS.any { host.contains(it) }) {
                return true
            }
            if (provider != DnsProvider.QUAD9) {
                // Cloudflare security does not block standard ads by default
                return false
            }
        }

        // Ad blocking for AdGuard, AdGuard Family, NextDNS, and Custom
        if (provider.blocksAds) {
            // Check direct ad domain or subdomains
            for (adDomain in AD_DOMAINS) {
                if (host == adDomain || host.endsWith(".$adDomain")) {
                    return true
                }
            }

            // Check common ad host prefixes
            for (prefix in AD_HOST_PREFIXES) {
                if (host.startsWith(prefix)) {
                    return true
                }
            }

            // Check ad resource path keywords
            for (kw in AD_PATH_KEYWORDS) {
                if (path.contains(kw)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Injected cosmetic JavaScript stylesheet to collapse residual empty ad containers.
     */
    fun getAdHidingScript(): String {
        return """
            (function() {
                try {
                    if (window.__adFilterApplied) return;
                    window.__adFilterApplied = true;
                    var css = `
                        .ads, .ad, .adsbox, .ad-banner, .advertisement,
                        iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
                        iframe[src*="adservice"], iframe[src*="ads"],
                        [id^="google_ads_"], [class*="google-ad"],
                        [class*="banner-ad"], .native-ad, .ad-slot,
                        div[data-ad-unit], div[data-ad-client] {
                            display: none !important;
                            visibility: hidden !important;
                            height: 0 !important;
                            min-height: 0 !important;
                            opacity: 0 !important;
                            pointer-events: none !important;
                        }
                    `;
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.appendChild(document.createTextNode(css));
                    (document.head || document.documentElement).appendChild(style);
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
