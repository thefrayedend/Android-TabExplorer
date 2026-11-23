package com.tabexplorer.android

import android.content.Context
import android.os.Build
import com.tabexplorer.core.Sort
import com.tabexplorer.core.SortBy
import java.io.File

class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("tabexplorer", Context.MODE_PRIVATE)

    var deviceName: String
        get() = p.getString("deviceName", null) ?: (Build.MODEL ?: "Android")
        set(v) { p.edit().putString("deviceName", v).apply() }
    /** "Ready to receive": the foreground service keeps sharing on while the app is closed. */
    var ready: Boolean
        get() = p.getBoolean("ready", false)
        set(v) { p.edit().putBoolean("ready", v).apply() }
    var keepBoth: Boolean
        get() = p.getBoolean("keepBoth", true)
        set(v) { p.edit().putBoolean("keepBoth", v).apply() }
    var grid: Boolean
        get() = p.getBoolean("grid", false)
        set(v) { p.edit().putBoolean("grid", v).apply() }
    var showHidden: Boolean
        get() = p.getBoolean("showHidden", false)
        set(v) { p.edit().putBoolean("showHidden", v).apply() }
    var sort: Sort
        get() = Sort(runCatching { SortBy.valueOf(p.getString("sortBy", "Name")!!) }.getOrDefault(SortBy.Name), p.getBoolean("sortDesc", false))
        set(v) { p.edit().putString("sortBy", v.by.name).putBoolean("sortDesc", v.descending).apply() }

    private fun list(key: String): List<String> = (p.getString(key, "") ?: "").split('\n').filter { it.isNotEmpty() }
    private fun putList(key: String, l: List<String>) { p.edit().putString(key, l.joinToString("\n")).apply() }

    var pins: List<File>
        get() = list("pins").map { File(it) }.filter { it.isDirectory }
        set(v) = putList("pins", v.map { it.path })
    fun isPinned(f: File) = list("pins").contains(f.path)
    fun togglePin(f: File) { val l = list("pins").toMutableList(); if (!l.remove(f.path)) l.add(f.path); putList("pins", l) }

    /** Recent places (folders visited), newest first. */
    val recentPlaces: List<File> get() = list("recentPlaces").map { File(it) }.filter { it.isDirectory }
    fun visited(dir: File) { val l = list("recentPlaces").toMutableList(); l.remove(dir.path); l.add(0, dir.path); putList("recentPlaces", l.take(12)) }

    val recentFiles: List<File> get() = list("recentFiles").map { File(it) }.filter { it.isFile }
    fun opened(f: File) { val l = list("recentFiles").toMutableList(); l.remove(f.path); l.add(0, f.path); putList("recentFiles", l.take(30)) }
}
