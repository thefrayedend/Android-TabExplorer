package com.tabexplorer.share

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

object TestSupport {
    fun freePort(): Int = ServerSocket(0).use { it.localPort }
    fun tmp(name: String): File = Files.createTempDirectory(name).toFile()

    /** Walks a folder as the items to send (folders first), the way the app does. */
    fun itemsOf(vararg roots: File): List<SendItem> {
        val out = ArrayList<SendItem>()
        fun walk(f: File, rel: String) {
            if (f.isDirectory) { out.add(SendItem(rel, true)); f.listFiles()!!.sortedBy { it.name }.forEach { walk(it, "$rel/${it.name}") } }
            else out.add(SendItem(rel, false, f.length(), f.lastModified()) { f.inputStream() })
        }
        roots.forEach { walk(it, it.name) }
        return out
    }

    fun tree(root: File): Map<String, String> {
        val m = sortedMapOf<String, String>()
        root.walkTopDown().filter { it.isFile }.forEach { m[it.relativeTo(root).path.replace('\\', '/')] = it.readText() }
        return m
    }

    fun share(dir: File, folder: File, name: String, keepBoth: Boolean = true, ask: (AskRequest) -> Boolean = { true }, udp: Int = freePort()): Share =
        Share(Identity.load(File(dir, "share.json")), { ShareConfig(true, name, folder, keepBoth) }, ask, { println("[$name] $it") }, tcpPort = freePort(), udpPort = udp)

    fun waitFor(ms: Long = 5000, cond: () -> Boolean) { val end = System.currentTimeMillis() + ms; while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20) }
}
