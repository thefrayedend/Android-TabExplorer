package com.tabexplorer.android

import android.content.Context
import android.provider.MediaStore
import com.tabexplorer.core.Kind
import java.io.File

/** Everything of a type on the phone, from Android's media index: instant, no folder walking. */
object MediaIndex {
    fun query(ctx: Context, kind: Kind, limit: Int = 5000): List<File> {
        val uri = MediaStore.Files.getContentUri("external")
        val (sel, args) = when (kind) {
            Kind.Image -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            Kind.Video -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            Kind.Audio -> "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?" to arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString())
            Kind.Apk -> "${MediaStore.Files.FileColumns.DATA} LIKE ?" to arrayOf("%.apk")
            Kind.Archive -> "(" + listOf("zip", "7z", "rar", "tar", "gz").joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE '%.$it'" } + ")" to emptyArray()
            Kind.Pdf -> "${MediaStore.Files.FileColumns.DATA} LIKE ?" to arrayOf("%.pdf")
            else -> "(" + listOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "txt", "rtf").joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE '%.$it'" } + ")" to emptyArray()
        }
        val out = ArrayList<File>()
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) { val p = c.getString(0) ?: continue; val f = File(p); if (f.isFile) out.add(f) }
            }
        } catch (_: Exception) { }
        return out
    }
}
