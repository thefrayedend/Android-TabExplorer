package com.tabexplorer.android

import android.content.Context
import android.os.Environment
import android.os.StatFs
import com.tabexplorer.core.Kind
import java.io.File

class Volume(val name: String, val root: File, val removable: Boolean) {
    val total: Long get() = runCatching { StatFs(root.path).totalBytes }.getOrDefault(0L)
    val free: Long get() = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)
}

object Storage {
    val primary: File get() = Environment.getExternalStorageDirectory()

    /** Phone storage, then SD cards and USB sticks (found through the app's own folders on each volume). */
    fun volumes(ctx: Context): List<Volume> {
        val out = ArrayList<Volume>()
        out.add(Volume("Phone", primary, false))
        val seen = HashSet<String>(); seen.add(primary.canonicalPath)
        for (d in ctx.getExternalFilesDirs(null)) {
            if (d == null) continue
            val i = d.path.indexOf("/Android/data")
            if (i <= 0) continue
            val root = File(d.path.substring(0, i))
            if (!seen.add(runCatching { root.canonicalPath }.getOrDefault(root.path))) continue
            if (!root.exists()) continue
            out.add(Volume(if (root.name.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) "SD card / USB (${root.name})" else root.name, root, true))
        }
        return out
    }

    class Quick(val label: String, val dir: File)
    fun quickPlaces(): List<Quick> {
        val p = primary
        return listOf(
            Quick("Camera", File(p, "DCIM/Camera")), Quick("Downloads", File(p, "Download")),
            Quick("Screenshots", File(p, "Pictures/Screenshots").let { if (it.exists()) it else File(p, "DCIM/Screenshots") }),
            Quick("Music", File(p, "Music")), Quick("Movies", File(p, "Movies")), Quick("Documents", File(p, "Documents")),
        ).filter { it.dir.isDirectory }
    }

    /** Where received files go; photos and videos show in the Gallery once scanned. */
    val receiveFolder: File get() = File(primary, "Download/TabExplorer")

    fun breadcrumb(f: File): String = f.path.removePrefix(primary.path).ifEmpty { "/" }.let { if (f.path.startsWith(primary.path)) "Phone$it" else f.path }

    fun isFat(f: File): Boolean = f.path.startsWith(primary.path).not()
    fun kindLabel(k: Kind) = when (k) { Kind.Folder -> "Folder"; Kind.Image -> "Image"; Kind.Video -> "Video"; Kind.Audio -> "Audio"; Kind.Document -> "Document"; Kind.Text -> "Text"; Kind.Pdf -> "PDF"; Kind.Apk -> "App (APK)"; Kind.Archive -> "Archive"; Kind.Other -> "File" }
}
