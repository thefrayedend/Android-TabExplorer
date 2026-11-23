package com.tabexplorer.core

import java.io.File

enum class SortBy { Name, Date, Size, Type }
data class Sort(val by: SortBy = SortBy.Name, val descending: Boolean = false)

object Sorting {
    /** Natural order: "file2" before "file10", ignoring case. */
    val natural = Comparator<String> { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                val c = if (na.length != nb.length) na.length.compareTo(nb.length) else na.compareTo(nb)
                if (c != 0) return@Comparator c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return@Comparator c
                i++; j++
            }
        }
        (a.length - i).compareTo(b.length - j)
    }

    fun extension(f: File): String { val n = f.name; val d = n.lastIndexOf('.'); return if (d <= 0) "" else n.substring(d + 1).lowercase() }

    /** Folders always first, then the chosen order. */
    fun sort(files: List<File>, s: Sort): List<File> {
        val base: Comparator<File> = when (s.by) {
            SortBy.Name -> Comparator { a, b -> natural.compare(a.name, b.name) }
            SortBy.Date -> Comparator { a, b -> a.lastModified().compareTo(b.lastModified()) }
            SortBy.Size -> Comparator { a, b -> (if (a.isDirectory) 0L else a.length()).compareTo(if (b.isDirectory) 0L else b.length()) }
            SortBy.Type -> Comparator { a, b -> extension(a).compareTo(extension(b)) }
        }
        val dir = if (s.descending) base.reversed() else base
        return files.sortedWith(Comparator { a, b ->
            if (a.isDirectory != b.isDirectory) if (a.isDirectory) -1 else 1
            else { val c = dir.compare(a, b); if (c != 0) c else natural.compare(a.name, b.name) }
        })
    }
}

enum class Kind { Folder, Image, Video, Audio, Document, Text, Pdf, Apk, Archive, Other }

object Kinds {
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val video = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v", "ts", "flv", "wmv")
    private val audio = setOf("mp3", "flac", "ogg", "oga", "opus", "m4a", "aac", "wav", "wma", "mid", "midi")
    private val doc = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf")
    private val text = setOf("txt", "md", "log", "json", "xml", "csv", "ini", "cfg", "conf", "yml", "yaml", "html", "htm", "css", "js", "kt", "java", "py", "sh", "bat", "svg", "gcode", "srt", "vtt", "lrc", "properties", "gradle", "toml")
    private val archive = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")
    fun of(f: java.io.File): Kind = if (f.isDirectory) Kind.Folder else ofName(f.name)
    fun ofName(name: String): Kind {
        val d = name.lastIndexOf('.'); val e = if (d <= 0) "" else name.substring(d + 1).lowercase()
        return when (e) {
            in image -> Kind.Image; in video -> Kind.Video; in audio -> Kind.Audio; in doc -> Kind.Document
            "pdf" -> Kind.Pdf; "apk" -> Kind.Apk; in archive -> Kind.Archive; in text -> Kind.Text; else -> Kind.Other
        }
    }
}

fun humanSize(n: Long): String {
    if (n < 1024) return "$n B"
    val u = arrayOf("KB", "MB", "GB", "TB"); var v = n.toDouble() / 1024; var i = 0
    while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
    return (if (v >= 100) "%.0f" else if (v >= 10) "%.1f" else "%.2f").format(java.util.Locale.US, v) + " " + u[i]
}
