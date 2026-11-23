package com.tabexplorer.core

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.UUID

class TrashItem(val id: String, val name: String, val originalPath: String, val deletedAt: Long, val size: Long, val isDir: Boolean)

/** The app's own bin: deleting moves here, and anything older than [keepDays] is removed for good. */
class RecycleBin(private val dir: File, private val keepDays: Int = 30, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val gson = Gson()
    private fun data(id: String) = File(dir, "$id.d")
    private fun meta(id: String) = File(dir, "$id.json")

    fun trash(f: File): TrashItem {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val size = FileOps.sizeOf(f); val isDir = f.isDirectory
        val item = TrashItem(id, f.name, f.canonicalPath, now(), size, isDir)
        val d = data(id)
        if (!f.renameTo(d)) {
            val r = FileOps.move(listOf(f), dir, { Conflict.KeepBoth })
            val moved = r.done.firstOrNull() ?: throw IOException("Couldn't move ${f.name} to the Recycle Bin.")
            if (r.errors.isNotEmpty()) { FileOps.deleteRecursive(moved); throw IOException(r.errors.first().second) }
            if (!moved.renameTo(d)) throw IOException("Couldn't move ${f.name} to the Recycle Bin.")
        }
        meta(id).writeText(gson.toJson(item))
        return item
    }

    fun list(): List<TrashItem> = (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).mapNotNull {
        try { gson.fromJson(it.readText(), TrashItem::class.java) } catch (_: Exception) { null }
    }.sortedByDescending { it.deletedAt }

    /** Puts it back where it was (or beside it with a new name if that name is taken). Returns the restored file. */
    fun restore(item: TrashItem, conflict: Conflict = Conflict.KeepBoth): File {
        val orig = File(item.originalPath)
        orig.parentFile?.mkdirs()
        var t = orig
        if (t.exists()) {
            when (conflict) {
                Conflict.KeepBoth -> t = FileOps.uniqueIn(orig.parentFile!!, orig.name)
                Conflict.Replace -> FileOps.deleteRecursive(t)
                Conflict.Skip -> throw IOException("${orig.name} is already there.")
            }
        }
        val d = data(item.id)
        if (!d.renameTo(t)) {
            val tmpNamed = File(dir, "restore-" + item.id + "/" + t.name)
            tmpNamed.parentFile.mkdirs()
            if (!d.renameTo(tmpNamed)) throw IOException("Couldn't restore ${item.name}.")
            val r = FileOps.move(listOf(tmpNamed), t.parentFile!!, { Conflict.KeepBoth })
            tmpNamed.parentFile.delete()
            if (r.errors.isNotEmpty()) throw IOException(r.errors.first().second)
        }
        meta(item.id).delete()
        return t
    }

    fun purge(item: TrashItem) { FileOps.deleteRecursive(data(item.id)); meta(item.id).delete() }
    fun empty() { list().forEach { purge(it) } }

    /** Removes what has been in the bin longer than [keepDays]. Returns how many went. */
    fun purgeOld(): Int {
        val limit = now() - keepDays * 24L * 3600 * 1000
        val old = list().filter { it.deletedAt < limit }
        old.forEach { purge(it) }
        return old.size
    }
}
