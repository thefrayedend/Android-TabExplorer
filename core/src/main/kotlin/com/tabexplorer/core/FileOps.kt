package com.tabexplorer.core

import java.io.File
import java.io.IOException

enum class Conflict { Replace, Skip, KeepBoth }

class Cancelled : IOException("cancelled")

class OpProgress(var doneBytes: Long = 0, var totalBytes: Long = 0, var doneFiles: Int = 0, var totalFiles: Int = 0, var current: String = "") 

class OpResult(val done: List<File>, val errors: List<Pair<String, String>>, val skipped: Int)

/** Copy, move, rename, delete: plain java.io, so it is tested on a JVM. */
object FileOps {
    fun sizeOf(f: File): Long = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { sizeOf(it) } else f.length()
    fun countFiles(f: File): Int = if (f.isDirectory) (f.listFiles() ?: emptyArray()).sumOf { countFiles(it) } else 1

    fun uniqueIn(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) { val c = File(dir, "$stem ($i)$ext"); if (!c.exists()) return c; i++ }
    }

    fun validName(name: String): String? = when {
        name.isBlank() -> "A name can't be empty."
        name == "." || name == ".." -> "That name can't be used."
        name.contains('/') || name.contains('\u0000') -> "A name can't contain /."
        name.length > 255 -> "That name is too long."
        else -> null
    }

    fun rename(f: File, newName: String): File {
        validName(newName)?.let { throw IOException(it) }
        val t = File(f.parentFile, newName)
        if (t.exists() && !t.canonicalPath.equals(f.canonicalPath, ignoreCase = false)) throw IOException("\"$newName\" already exists here.")
        if (!f.renameTo(t)) throw IOException("Couldn't rename it.")
        return t
    }

    fun newFolder(parent: File, name: String): File {
        validName(name)?.let { throw IOException(it) }
        val t = File(parent, name)
        if (t.exists()) throw IOException("\"$name\" already exists here.")
        if (!t.mkdirs()) throw IOException("Couldn't make the folder.")
        return t
    }

    fun newFile(parent: File, name: String): File {
        validName(name)?.let { throw IOException(it) }
        val t = File(parent, name)
        if (t.exists()) throw IOException("\"$name\" already exists here.")
        if (!t.createNewFile()) throw IOException("Couldn't make the file.")
        return t
    }

    fun deleteRecursive(f: File) {
        if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { deleteRecursive(it) }
        if (!f.delete() && f.exists()) throw IOException("Couldn't delete ${f.name}.")
    }

    /** True when [dir] is [src] or inside it (a folder can't go into itself). */
    fun isInside(src: File, dir: File): Boolean {
        val s = src.canonicalFile; val d = dir.canonicalFile
        return d == s || d.path.startsWith(s.path + File.separator)
    }

    /**
     * Copies [sources] into [destDir]. [resolve] says what to do when a name is taken (it may block on a dialog).
     * Folders merge. Returns what was made.
     */
    fun copy(sources: List<File>, destDir: File, resolve: (File) -> Conflict, progress: OpProgress = OpProgress(), canceled: () -> Boolean = { false }, move: Boolean = false): OpResult {
        progress.totalBytes = sources.sumOf { sizeOf(it) }; progress.totalFiles = sources.sumOf { countFiles(it) }
        val done = ArrayList<File>(); val errors = ArrayList<Pair<String, String>>(); var skipped = 0
        val buf = ByteArray(256 * 1024)

        fun copyFile(s: File, t: File) {
            progress.current = s.name
            val tmp = File(t.parentFile, t.name + ".txpart")
            try {
                s.inputStream().use { i -> tmp.outputStream().use { o ->
                    while (true) {
                        if (canceled()) throw Cancelled()
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); progress.doneBytes += n
                    }
                } }
                if (t.exists()) t.delete()
                if (!tmp.renameTo(t)) throw IOException("Couldn't finish writing ${t.name}.")
                t.setLastModified(s.lastModified())
            } catch (e: Exception) { tmp.delete(); throw e }
            progress.doneFiles++
        }

        // returns true when everything under s was handled (so a move may remove it)
        fun one(s: File, targetDir: File, topLevel: Boolean): Boolean {
            if (canceled()) throw Cancelled()
            var t = File(targetDir, s.name)
            if (s.isDirectory) {
                if (isInside(s, t)) { errors.add(s.name to "A folder can't go into itself."); return false }
                if (t.exists() && !t.isDirectory) {
                    when (resolve(t)) { Conflict.Skip -> { skipped++; return false }; Conflict.KeepBoth -> t = uniqueIn(targetDir, s.name); Conflict.Replace -> t.delete() }
                }
                t.mkdirs()
                var all = true
                for (c in s.listFiles() ?: emptyArray()) { try { if (!one(c, t, false)) all = false } catch (e: Cancelled) { throw e } catch (e: Exception) { errors.add(c.name to (e.message ?: e.toString())); all = false } }
                if (topLevel) done.add(t)
                if (move && all) s.delete()
                return all
            }
            if (t.exists()) {
                when (resolve(t)) {
                    Conflict.Skip -> { skipped++; progress.doneBytes += s.length(); progress.doneFiles++; return false }
                    Conflict.KeepBoth -> t = uniqueIn(targetDir, s.name)
                    Conflict.Replace -> { if (t.isDirectory) deleteRecursive(t) }
                }
            }
            copyFile(s, t)
            if (topLevel) done.add(t)
            if (move) s.delete()
            return true
        }

        try {
            for (s in sources) {
                try { one(s, destDir, true) } catch (e: Cancelled) { throw e } catch (e: Exception) { errors.add(s.name to (e.message ?: e.toString())) }
            }
        } catch (e: Cancelled) { errors.add("" to "Cancelled.") }
        return OpResult(done, errors, skipped)
    }

    /** Move: a quick rename when it works, otherwise copy then remove. */
    fun move(sources: List<File>, destDir: File, resolve: (File) -> Conflict, progress: OpProgress = OpProgress(), canceled: () -> Boolean = { false }): OpResult {
        val rest = ArrayList<File>(); val done = ArrayList<File>(); val errors = ArrayList<Pair<String, String>>(); var skipped = 0
        for (s in sources) {
            val t = File(destDir, s.name)
            if (s.isDirectory && isInside(s, destDir)) { errors.add(s.name to "A folder can't go into itself."); continue }
            if (s.canonicalFile.parentFile == destDir.canonicalFile) { skipped++; continue }
            if (!t.exists() && s.renameTo(t)) { done.add(t); continue }
            rest.add(s)
        }
        if (rest.isNotEmpty()) {
            val r = copy(rest, destDir, resolve, progress, canceled, move = true)
            done.addAll(r.done); errors.addAll(r.errors); skipped += r.skipped
        }
        return OpResult(done, errors, skipped)
    }
}
