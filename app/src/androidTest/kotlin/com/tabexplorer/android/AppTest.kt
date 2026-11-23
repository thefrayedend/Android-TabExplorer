package com.tabexplorer.android

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real app on an emulator and drives it like a person would. */
@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val root = File(Environment.getExternalStorageDirectory(), "AAATx")

    @Before fun setUp() {
        root.deleteRecursively(); File(root, "sub").mkdirs()
        File(root, "a.txt").writeText("hello a"); File(root, "b.txt").writeText("hello b")
        App.instance.bin.empty()
        App.instance.prefs.showHidden = false
    }

    private fun openRoot() {
        rule.onNodeWithText("Phone").performClick()
        rule.until(10_000) { rule.onAllNodesWithText("AAATx").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("AAATx").performClick()
        rule.until(10_000) { rule.onAllNodesWithText("a.txt").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitText(t: String, ms: Long = 10_000) = rule.until(ms) { rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty() }
    private fun waitGone(t: String, ms: Long = 10_000) = rule.until(ms) { rule.onAllNodesWithText(t).fetchSemanticsNodes().isEmpty() }

    @Test fun homeShowsStorageAndPlaces() {
        rule.onNodeWithText("TabExplorer").assertIsDisplayed()
        rule.onNodeWithText("Storage").assertIsDisplayed()
        rule.onNodeWithText("Phone").assertIsDisplayed()
        rule.onNodeWithText("Quick places").assertExists()
        rule.onNodeWithContentDescription("Recycle Bin").assertExists()
    }

    @Test fun browseListsFilesFoldersFirst() {
        openRoot()
        rule.onNodeWithText("sub").assertIsDisplayed(); rule.onNodeWithText("b.txt").assertIsDisplayed()
    }

    @Test fun deleteGoesToBinAndUndoBringsItBack() {
        openRoot()
        rule.onNodeWithText("a.txt").performTouchInput { longClick() }
        waitText("1 selected")
        rule.onNodeWithText("Delete").performClick()
        waitGone("a.txt")
        assertFalse(File(root, "a.txt").exists())
        assertEquals(1, App.instance.bin.list().size)
        waitText("moved to the Recycle Bin")
        rule.onNodeWithText("Undo").performClick()
        waitText("a.txt")
        assertEquals("hello a", File(root, "a.txt").readText())
        assertEquals(0, App.instance.bin.list().size)
    }

    @Test fun copyPasteIntoAFolder() {
        openRoot()
        rule.onNodeWithText("b.txt").performTouchInput { longClick() }
        rule.onNodeWithText("Copy").performClick()
        rule.onNodeWithText("sub").performClick()
        waitText("This folder is empty")
        rule.onNodeWithText("Paste here").performClick()
        rule.until(15_000) { File(root, "sub/b.txt").exists() }
        assertEquals("hello b", File(root, "sub/b.txt").readText())
        assertTrue(File(root, "b.txt").exists())
    }

    @Test fun moveAFile() {
        openRoot()
        rule.onNodeWithText("b.txt").performTouchInput { longClick() }
        rule.onNodeWithText("Move").performClick()
        rule.onNodeWithText("sub").performClick()
        waitText("This folder is empty")
        rule.onNodeWithText("Paste here").performClick()
        rule.until(15_000) { File(root, "sub/b.txt").exists() }
        assertFalse(File(root, "b.txt").exists())
    }

    @Test fun newFolderAndRename() {
        openRoot()
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("New folder").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("Fresh")
        rule.onNodeWithText("Create").performClick()
        rule.until(10_000) { File(root, "Fresh").isDirectory }
        waitText("Fresh")
        rule.onNodeWithText("a.txt").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Rename").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("renamed.txt")
        rule.onAllNodesWithText("Rename").onLast().performClick()
        rule.until(10_000) { File(root, "renamed.txt").exists() }
        assertFalse(File(root, "a.txt").exists())
    }

    @Test fun textFileOpensEditsAndSaves() {
        openRoot()
        rule.onNodeWithText("a.txt").performClick()
        waitText("hello a")
        rule.onNode(hasSetTextAction()).performTextReplacement("changed")
        rule.onNodeWithContentDescription("Save").performClick()
        rule.until(10_000) { File(root, "a.txt").readText() == "changed" }
    }

    @Test fun photoOpensInTheViewer() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.RED) }
        File(root, "red.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        openRoot()
        rule.onNodeWithText("red.png").performClick()
        waitText("1 of 1")
        rule.onNodeWithContentDescription("Send to PC").assertExists()
    }

    @Test fun pdfOpens() {
        val doc = PdfDocument(); val page = doc.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
        page.canvas.drawText("Hello PDF", 20f, 40f, android.graphics.Paint()); doc.finishPage(page)
        File(root, "doc.pdf").outputStream().use { doc.writeTo(it) }; doc.close()
        openRoot()
        rule.onNodeWithText("doc.pdf").performClick()
        rule.until(10_000) { rule.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun musicPlaysInTheBackgroundService() {
        File(root, "tone.wav").writeBytes(wav(3))
        openRoot()
        rule.onNodeWithText("tone.wav").performClick()
        waitText("Now playing")
        rule.until(15_000) { Player.count == 1 && Player.playing }
        rule.onNodeWithContentDescription("Play/pause").performClick()
        rule.until(5_000) { !Player.playing }
        // leave the screen: the music service (and its media session) still exist
        androidx.test.espresso.Espresso.pressBack()
        assertEquals(1, Player.count)
    }

    @Test fun recycleBinScreenListsAndRestores() {
        App.instance.bin.trash(File(root, "b.txt"))
        rule.onNodeWithContentDescription("Recycle Bin").performClick()
        waitText("b.txt")
        rule.onNodeWithContentDescription("Restore").performClick()
        rule.until(10_000) { File(root, "b.txt").exists() }
    }

    @Test fun sharingScreenShowsCodeAndSwitch() {
        rule.onNodeWithContentDescription("Sharing").performClick()
        waitText("Ready to receive")
        waitText("Code ${App.instance.share.code}")
        assertTrue(App.instance.share.running)
    }

    private fun wav(seconds: Int): ByteArray {
        val rate = 8000; val n = rate * seconds
        val b = java.nio.ByteBuffer.allocate(44 + n * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n * 2)
        for (i in 0 until n) b.putShort((Math.sin(i * 2 * Math.PI * 440 / rate) * 8000).toInt().toShort())
        return b.array()
    }
}
