package com.kimiterrace.tvbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val configured = "https://app.school-signage.net/signage/AbCdEf123?design=pattern2"
    private val legacyConfigured = "https://www.school-signage.net/?school=x&kiosk=1"

    private fun allowed(url: String?, cfg: String? = configured) = NavigationPolicy.isAllowed(url, cfg)

    // --- 許可 ---

    @Test fun sameHost_samePage() = assertTrue(allowed(configured))

    @Test fun sameHost_otherPath() = assertTrue(allowed("https://app.school-signage.net/signage/other?x=1#y"))

    @Test fun sameHost_caseInsensitive() = assertTrue(allowed("HTTPS://APP.School-Signage.NET/signage/t"))

    @Test fun sameHost_explicitDefaultPort() = assertTrue(allowed("https://app.school-signage.net:443/signage/t"))

    @Test fun legacyConfiguredHost_isAllowed() =
        assertTrue(allowed("https://www.school-signage.net/foo", legacyConfigured))

    @Test fun productionHost_allowedEvenWhenConfigIsLegacy() =
        assertTrue(allowed("https://app.school-signage.net/signage/t", legacyConfigured))

    @Test fun productionHost_allowedWhenConfigBlankOrNull() {
        assertTrue(allowed("https://app.school-signage.net/signage/t", ""))
        assertTrue(allowed("https://app.school-signage.net/signage/t", null))
    }

    // --- 他ホスト ---

    @Test fun otherHost_blocked() = assertFalse(allowed("https://evil.com/"))

    @Test fun advertiserLink_blocked() = assertFalse(allowed("https://www.example.co.jp/recruit"))

    @Test fun legacyHost_blockedWhenNotConfigured() =
        assertFalse(allowed("https://www.school-signage.net/"))

    @Test fun parentDomain_blocked() = assertFalse(allowed("https://school-signage.net/"))

    @Test fun siblingSubdomain_blocked() = assertFalse(allowed("https://admin.school-signage.net/"))

    @Test fun nonDefaultPort_blocked() = assertFalse(allowed("https://app.school-signage.net:8443/"))

    // --- ダウングレード ---

    @Test fun httpDowngrade_blocked() = assertFalse(allowed("http://app.school-signage.net/signage/t"))

    @Test fun httpConfiguredUrl_doesNotWidenAllowlist() {
        val httpCfg = "http://www.school-signage.net/"
        assertFalse(allowed("http://www.school-signage.net/", httpCfg))
        assertFalse(allowed("https://www.school-signage.net/", httpCfg))
        assertEquals(setOf(NavigationPolicy.PRODUCTION_SIGNAGE_HOST), NavigationPolicy.allowedHosts(httpCfg))
    }

    // --- 偽装 ---

    @Test fun suffixTrick_blocked() = assertFalse(allowed("https://app.school-signage.net.evil.com/"))

    @Test fun prefixTrick_blocked() = assertFalse(allowed("https://evilapp.school-signage.net/"))

    @Test fun userinfoTrick_blocked() = assertFalse(allowed("https://app.school-signage.net@evil.com/"))

    @Test fun userinfoTrick_withPassword_blocked() =
        assertFalse(allowed("https://app.school-signage.net:pw@evil.com/"))

    @Test fun userinfo_onAllowedHost_blocked() = assertFalse(allowed("https://user@app.school-signage.net/"))

    @Test fun backslashTrick_blocked() = assertFalse(allowed("https://app.school-signage.net\\@evil.com/"))

    @Test fun trailingDotHost_blocked() = assertFalse(allowed("https://app.school-signage.net./"))

    @Test fun protocolRelative_blocked() = assertFalse(allowed("//app.school-signage.net/"))

    // --- スキーム ---

    @Test fun nonHttpSchemes_blocked() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "intent:#Intent;action=android.settings.SETTINGS;end",
            "market://details?id=com.evil",
            "javascript:alert(1)",
            "file:///sdcard/Download/x.html",
            "content://com.android.providers.downloads/x",
            "data:text/html,<script>alert(1)</script>",
            "about:blank",
            "chrome://settings",
            "ftp://app.school-signage.net/",
            "wss://app.school-signage.net/",
        ).forEach { assertFalse(it, allowed(it)) }
    }

    @Test fun garbage_blocked() {
        listOf(null, "", "   ", "not a url", "https://", "https:///path", "https://exa mple.com/").forEach {
            assertFalse("$it", allowed(it))
        }
    }

    // --- ログ伏字 ---

    @Test fun redactForLog_dropsQueryAndUserinfo() {
        assertEquals("https://app.school-signage.net", NavigationPolicy.redactForLog(configured))
        assertEquals("https://evil.com", NavigationPolicy.redactForLog("https://u:p@evil.com/x?key=secret"))
        assertEquals("javascript:(opaque)", NavigationPolicy.redactForLog("javascript:alert(1)"))
    }
}
