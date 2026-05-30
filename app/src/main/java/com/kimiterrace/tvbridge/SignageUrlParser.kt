package com.kimiterrace.tvbridge

import android.net.Uri

/**
 * signage URL のクエリパラメータから教室コンテキストを抽出する。
 *
 * 例:
 *   https://app.school-signage.net/?school=5tdbeclk5fce
 *     &grade=dIvlTT7sN57UMmBN5X2x
 *     &department=fJJRKRdyLnNLattNzSsX
 *     &class=BnsfTb7hjAzA4XIYt73i
 *     &kiosk=1
 * → ClassroomContext(schoolId=..., gradeId=..., departmentId=..., classId=...)
 */
object SignageUrlParser {

    data class ClassroomContext(
        val schoolId: String?,
        val gradeId: String?,
        val departmentId: String?,
        val classId: String?,
    )

    fun parse(url: String): ClassroomContext? {
        return try {
            val uri = Uri.parse(url)
            ClassroomContext(
                schoolId = uri.getQueryParameter("school")?.takeIf { it.isNotBlank() },
                gradeId = uri.getQueryParameter("grade")?.takeIf { it.isNotBlank() },
                departmentId = uri.getQueryParameter("department")?.takeIf { it.isNotBlank() },
                classId = uri.getQueryParameter("class")?.takeIf { it.isNotBlank() },
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 一覧表記。"電子工学科 1年" 相当の label を作る基礎情報。
     * 実際の名前解決は LP 側で device_id → label のマッピング表に依存する想定。
     */
    fun summarize(ctx: ClassroomContext): String {
        val parts = listOfNotNull(
            ctx.departmentId?.let { "dept=${it.take(8)}" },
            ctx.gradeId?.let { "grade=${it.take(8)}" },
            ctx.classId?.let { "class=${it.take(8)}" },
        )
        return if (parts.isEmpty()) "(unknown)" else parts.joinToString(" / ")
    }
}
