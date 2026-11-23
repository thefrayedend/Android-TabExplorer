package com.tabexplorer.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabexplorer.share.SendJob
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The app on the emulator talking to TabExplorer's real sharing code, which runs on the build machine
 * (tools/ci/emulator-run.sh starts it). The emulator reaches the build machine at 10.0.2.2.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PcInteropTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val args get() = InstrumentationRegistry.getArguments()

    @Test fun a_phoneSendsToTabExplorer() {
        val id = args.getString("pcId") ?: return   // only when run by the CI script
        val share = App.instance.share
        rule.until(10_000) { share.running }
        share.addPeer(id, "Node PC", args.getString("pcPub")!!, "10.0.2.2", args.getString("pcPort")!!.toInt())
        val dir = File(App.instance.cacheDir, "tosend/Photos").also { it.deleteRecursively(); File(it, "2026").mkdirs() }
        File(dir, "a.txt").writeText("alpha from the phone")
        File(dir, "2026/b.bin").writeBytes(ByteArray(600_000) { (it * 5).toByte() })
        val job = SendJob(itemsOfFiles(listOf(dir)))
        share.send(id, job)
        assertEquals(2, job.sentCount)
        assertTrue(job.errors.toString(), job.errors.isEmpty())
    }

    @Test fun b_tabExplorerSendsToThePhone() {
        if (args.getString("pcId") == null) return
        val share = App.instance.share
        rule.until(10_000) { share.running }
        File(Storage.receiveFolder, "FromPc").deleteRecursively()
        // tell the build machine who we are, so it can send to us
        File("/sdcard/Download/tx-identity.json").writeText("""{"id":"${share.identity.id}","name":"Phone","pub":"${share.identity.pub}","address":"127.0.0.1","port":${share.port}}""")
        // the first time, the phone asks whether to accept
        rule.until(120_000) { rule.onAllNodesWithText("Accept").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Accept").performClick()
        rule.until(60_000) { File(Storage.receiveFolder, "FromPc/hello.txt").exists() && File(Storage.receiveFolder, "FromPc/data.bin").length() == 700_000L }
        assertEquals("hello from the PC", File(Storage.receiveFolder, "FromPc/hello.txt").readText().trim())
        assertTrue(share.identity.isTrusted(share.identity.trusted.keys.first(), share.identity.trusted.values.first().pub))
    }
}
