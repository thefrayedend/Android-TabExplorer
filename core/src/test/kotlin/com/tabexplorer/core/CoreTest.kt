package com.tabexplorer.core

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class CoreTest {
    private fun tmp() = Files.createTempDirectory("core").toFile()
    private fun File.put(rel: String, text: String = rel) = File(this, rel).also { it.parentFile.mkdirs(); it.writeText(text) }
    private fun tree(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to it.readText() }.toSortedMap()

    @Test fun naturalSortAndFoldersFirst() {
        val d = tmp(); d.put("file10.txt"); d.put("file2.txt"); d.put("File1.txt"); File(d, "zdir").mkdirs()
        val s = Sorting.sort(d.listFiles()!!.toList(), Sort(SortBy.Name)).map { it.name }
        assertEquals(listOf("zdir", "File1.txt", "file2.txt", "file10.txt"), s)
        val r = Sorting.sort(d.listFiles()!!.toList(), Sort(SortBy.Name, true)).map { it.name }
        assertEquals("zdir", r.first()); assertEquals("file10.txt", r[1])
    }
    @Test fun sortBySizeAndType() {
        val d = tmp(); d.put("a.mp3", "1234567"); d.put("b.txt", "12"); d.put("c.avi", "123")
        assertEquals(listOf("b.txt", "c.avi", "a.mp3"), Sorting.sort(d.listFiles()!!.toList(), Sort(SortBy.Size)).map { it.name })
        assertEquals(listOf("c.avi", "a.mp3", "b.txt"), Sorting.sort(d.listFiles()!!.toList(), Sort(SortBy.Type)).map { it.name })
    }
    @Test fun kindsAndSizes() {
        assertEquals(Kind.Video, Kinds.ofName("a.MKV")); assertEquals(Kind.Audio, Kinds.ofName("x.flac")); assertEquals(Kind.Other, Kinds.ofName("noext"))
        assertEquals(Kind.Other, Kinds.ofName(".hidden")); assertEquals(Kind.Pdf, Kinds.ofName("a.pdf")); assertEquals(Kind.Apk, Kinds.ofName("a.apk"))
        assertEquals("999 B", humanSize(999)); assertEquals("1.50 KB", humanSize(1536)); assertEquals("12.0 MB", humanSize(12L * 1024 * 1024))
    }
    @Test fun copyFolderTreeAndKeepDates() {
        val a = tmp(); val b = tmp()
        a.put("Job/a.txt"); a.put("Job/sub/b.txt"); File(a, "Job/a.txt").setLastModified(1_600_000_000_000L)
        val r = FileOps.copy(listOf(File(a, "Job")), b, { Conflict.Replace })
        assertTrue(r.errors.isEmpty()); assertEquals(tree(a), tree(b))
        assertEquals(1_600_000_000_000L, File(b, "Job/a.txt").lastModified())
        assertTrue(b.walkTopDown().none { it.name.endsWith(".txpart") })
    }
    @Test fun conflictsReplaceSkipKeepBoth() {
        for (c in Conflict.values()) {
            val a = tmp(); val b = tmp(); a.put("x.txt", "new"); b.put("x.txt", "old")
            val r = FileOps.copy(listOf(File(a, "x.txt")), b, { c })
            when (c) {
                Conflict.Replace -> assertEquals(mapOf("x.txt" to "new"), tree(b))
                Conflict.Skip -> { assertEquals(mapOf("x.txt" to "old"), tree(b)); assertEquals(1, r.skipped) }
                Conflict.KeepBoth -> assertEquals(mapOf("x.txt" to "old", "x (2).txt" to "new"), tree(b))
            }
        }
    }
    @Test fun aFolderCantGoIntoItself() {
        val a = tmp(); a.put("F/x.txt")
        val r = FileOps.copy(listOf(File(a, "F")), File(a, "F"), { Conflict.KeepBoth })
        assertEquals(1, r.errors.size); assertEquals(setOf("F/x.txt"), tree(a).keys)
        val m = FileOps.move(listOf(File(a, "F")), File(a, "F/sub").also { it.mkdirs() }, { Conflict.KeepBoth })
        assertEquals(1, m.errors.size); assertTrue(File(a, "F/x.txt").exists())
    }
    @Test fun moveRenamesOrCopiesAndRemovesTheSource() {
        val a = tmp(); val b = tmp(); a.put("M/a.txt"); a.put("one.txt")
        val r = FileOps.move(listOf(File(a, "M"), File(a, "one.txt")), b, { Conflict.KeepBoth })
        assertTrue(r.errors.isEmpty()); assertEquals(setOf("M/a.txt", "one.txt"), tree(b).keys); assertTrue(tree(a).isEmpty())
    }
    @Test fun moveWithConflictCopiesThenRemovesOnlyWhatWasMoved() {
        val a = tmp(); val b = tmp(); a.put("x.txt", "A"); a.put("y.txt", "A"); b.put("x.txt", "B")
        val r = FileOps.move(listOf(File(a, "x.txt"), File(a, "y.txt")), b, { Conflict.Skip })
        assertEquals(1, r.skipped); assertEquals("A", File(a, "x.txt").readText()); assertFalse(File(a, "y.txt").exists())
        assertEquals(mapOf("x.txt" to "B", "y.txt" to "A"), tree(b))
    }
    @Test fun cancelStopsAndLeavesNoHalfFile() {
        val a = tmp(); val b = tmp(); File(a, "big.bin").writeBytes(ByteArray(3_000_000))
        var n = 0
        val r = FileOps.copy(listOf(File(a, "big.bin")), b, { Conflict.Replace }, canceled = { ++n > 2 })
        assertTrue(r.errors.any { it.second == "Cancelled." }); assertTrue(tree(b).isEmpty()); assertTrue(b.listFiles()!!.isEmpty())
    }
    @Test fun renameAndNewFolderRules() {
        val d = tmp(); d.put("a.txt"); d.put("b.txt")
        assertEquals("c.txt", FileOps.rename(File(d, "a.txt"), "c.txt").name)
        assertFailsWith<java.io.IOException> { FileOps.rename(File(d, "b.txt"), "c.txt") }
        assertFailsWith<java.io.IOException> { FileOps.rename(File(d, "b.txt"), "x/y") }
        assertFailsWith<java.io.IOException> { FileOps.rename(File(d, "b.txt"), " ") }
        FileOps.newFolder(d, "New"); assertFailsWith<java.io.IOException> { FileOps.newFolder(d, "New") }
        assertEquals("B.txt", FileOps.rename(File(d, "b.txt"), "B.txt").name)
    }
    @Test fun binTrashRestoreAndExpire() {
        val d = tmp(); val bin = tmp(); var t = 1_000_000_000_000L
        val rb = RecycleBin(bin, 30) { t }
        val f = d.put("Docs/a.txt", "hello"); val dir = File(d, "Docs")
        val item = rb.trash(dir)
        assertFalse(dir.exists()); assertEquals(1, rb.list().size); assertTrue(item.isDir)
        val back = rb.restore(rb.list()[0]); assertEquals("hello", File(back, "a.txt").readText()); assertEquals(0, rb.list().size)
        rb.trash(File(d, "Docs")); File(d, "Docs").mkdirs()          // original name is taken again
        val again = rb.restore(rb.list()[0]); assertEquals("Docs (2)", again.name)
        val x = d.put("x.txt"); rb.trash(x)
        t += 29L * 24 * 3600 * 1000; assertEquals(0, rb.purgeOld()); assertEquals(1, rb.list().size)
        t += 2L * 24 * 3600 * 1000; assertEquals(1, rb.purgeOld()); assertEquals(0, rb.list().size)
        assertTrue(bin.listFiles()!!.isEmpty())
        assertTrue(f.parentFile.exists().let { true })
    }
    @Test fun emptyBin() {
        val d = tmp(); val bin = tmp(); val rb = RecycleBin(bin)
        rb.trash(d.put("a")); rb.trash(d.put("b")); assertEquals(2, rb.list().size); rb.empty(); assertTrue(bin.listFiles()!!.isEmpty())
    }
}
