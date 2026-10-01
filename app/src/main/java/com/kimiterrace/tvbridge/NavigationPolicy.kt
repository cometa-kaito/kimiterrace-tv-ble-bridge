package com.kimiterrace.tvbridge

import java.net.URI

/**
 * サイネージ WebView の「トップレベル遷移を許すか」を決める純関数群。
 *
 * Android API（android.net.Uri 等）に依存させず java.net.URI だけで判定する
 * → JVM 単体テスト（app/src/test）でそのまま検証できる。
 *
 * 許可条件（すべて満たすときだけ true）:
 *  - scheme が https（http へのダウングレード・intent:/market:/javascript:/file:/data:/about: 等は不可）
 *  - userinfo を含まない（`https://app.school-signage.net@evil.com` のような偽装を弾く）
 *  - host が「現在設定されている signage_url の host」または [PRODUCTION_SIGNAGE_HOST] と完全一致
 *    （大文字小文字は無視。`app.school-signage.net.evil.com` 等のサフィックス偽装は不一致で弾く）
 *  - port が既定（443）
 *  - URI として解釈できる（解釈できないものは安全側で不可）
 *
 * signage_url 自体が https でなければ、その host は許可リストに入れない（定数ホストのみ）。
 */
object NavigationPolicy {

    /** v2 本番のサイネージ配信ホスト。設定 URL が未設定・不正でもここだけは許す。 */
    const val PRODUCTION_SIGNAGE_HOST = "app.school-signage.net"

    private const val HTTPS = "https"
    private const val HTTPS_DEFAULT_PORT = 443

    /** トップレベル遷移として [url] を許可するか。 */
    fun isAllowed(url: String?, configuredSignageUrl: String?): Boolean {
        val target = parseHttps(url) ?: return false
        return target in allowedHosts(configuredSignageUrl)
    }

    /** 許可ホスト集合（小文字）。 */
    fun allowedHosts(configuredSignageUrl: String?): Set<String> {
        val hosts = linkedSetOf(PRODUCTION_SIGNAGE_HOST)
        parseHttps(configuredSignageUrl)?.let { hosts.add(it) }
        return hosts
    }

    /**
     * ログ用に URL を縮める（クエリ・フラグメント・userinfo を落とす）。
     * signage_url の token やクエリに載る秘密をログへ出さないため。
     */
    fun redactForLog(url: String?): String {
        if (url.isNullOrBlank()) return "(empty)"
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return "(unparseable, len=${url.length})"
        val scheme = uri.scheme ?: "(no-scheme)"
        val host = uri.host
        return if (host != null) "$scheme://$host" else "$scheme:(opaque)"
    }

    /**
     * https・userinfo 無し・既定ポートの URL なら小文字の host を返す。それ以外は null。
     */
    private fun parseHttps(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return null
        }
        if (!HTTPS.equals(uri.scheme, ignoreCase = true)) return null
        if (uri.isOpaque) return null
        // userinfo 偽装（https://good@evil）と、URI がサーバ権限部を解釈できなかったケースを弾く
        if (uri.rawUserInfo != null) return null
        if (uri.rawAuthority?.contains('@') == true) return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isEmpty()) return null
        val port = uri.port
        if (port != -1 && port != HTTPS_DEFAULT_PORT) return null
        return host
    }
}
