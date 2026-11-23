package com.tabexplorer.share

/** Cleaning of names that arrive from another device: never a way out of the receiving folder. */
object Names {
    private val FAT_BAD = Regex("[<>:\"|?*\\\\]")

    fun cleanName(s: String?): String =
        (s ?: "").replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim().take(40)

    fun safePart(s: String, fat: Boolean): String {
        var p = s.replace(Regex("[\\u0000-\\u001f]"), "")
        if (fat) p = p.replace(FAT_BAD, "_")
        p = p.replace("/", "_")
        if (fat) p = p.replace(Regex("[. ]+$"), "")
        if (p.isEmpty() || p == "." || p == "..") return ""
        return p.take(240)
    }

    /** The parts of a relative path, or null when it has an empty/.. part, or goes too deep. */
    fun safeRel(rel: String, fat: Boolean): List<String>? {
        val parts = rel.split("/").map { safePart(it, fat) }
        if (parts.isEmpty() || parts.size > 64 || parts.any { it.isEmpty() }) return null
        return parts
    }

    /** "name (2).ext", "name (3).ext" ... the first that isn't taken. */
    fun uniqueName(exists: (String) -> Boolean, name: String): String {
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0
        val stem = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        for (i in 2 until 10000) {
            val cand = "$stem ($i)$ext"
            if (!exists(cand)) return cand
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }
}
