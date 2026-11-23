package com.tabexplorer.share

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Our sharing against TabExplorer's real share.js (run by Node). Needs Node 18+ on the PATH. */
class InteropTest {
    private val interop = File(System.getProperty("user.dir")).resolve("../tools/interop").canonicalFile

    private fun node(vararg args: String): Process =
        ProcessBuilder(listOf("node", "interop.js") + args).directory(interop).redirectError(ProcessBuilder.Redirect.INHERIT).start()

    private fun lineStarting(p: Process, prefix: String, ms: Long = 15000): String {
        val r: BufferedReader = p.inputStream.bufferedReader()
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (r.ready()) { val l = r.readLine() ?: break; if (l.startsWith(prefix)) return l.removePrefix(prefix) }
            else Thread.sleep(20)
        }
        fail("node never printed $prefix")
    }
    private fun field(json: String, k: String) = com.google.gson.JsonParser.parseString(json).asJsonObject.get(k)

    @Test fun weSendToTabExplorer() {
        val out = TestSupport.tmp("node-out")
        val p = node("receive", TestSupport.tmp("node-id").path, out.path, TestSupport.freePort().toString(), TestSupport.freePort().toString())
        try {
            val ready = lineStarting(p, "READY ")
            val me = TestSupport.share(TestSupport.tmp("me"), TestSupport.tmp("me-in"), "Phone")
            me.start()
            val id = field(ready, "id").asString
            me.addPeer(id, "Node PC", field(ready, "pub").asString, "127.0.0.1", field(ready, "port").asInt)
            val src = TestSupport.tmp("src")
            File(src, "Photos/2026").mkdirs()
            File(src, "Photos/a.txt").writeText("alpha"); File(src, "Photos/2026/b.bin").writeBytes(ByteArray(700_000) { (it * 7).toByte() })
            val job = SendJob(TestSupport.itemsOf(File(src, "Photos")))
            me.send(id, job)
            assertEquals(2, job.sentCount); assertTrue(job.errors.isEmpty(), job.errors.toString())
            assertEquals("alpha", File(out, "Photos/a.txt").readText())
            assertContentEquals(File(src, "Photos/2026/b.bin").readBytes(), File(out, "Photos/2026/b.bin").readBytes())
            // a second send of the same name keeps both, as TabExplorer is set to here
            me.send(id, SendJob(TestSupport.itemsOf(File(src, "Photos"))))
            assertTrue(File(out, "Photos/a.txt").exists())
            me.stop()
        } finally { p.destroy() }
    }

    @Test fun tabExplorerSendsToUs() {
        val inbox = TestSupport.tmp("me-in")
        val asked = ArrayList<AskRequest>()
        val me = TestSupport.share(TestSupport.tmp("me"), inbox, "Phone", ask = { asked.add(it); true })
        me.start()
        val src = TestSupport.tmp("src")
        File(src, "Job 1").mkdirs(); File(src, "Job 1/cut.svg").writeText("<svg/>")
        File(src, "Job 1/data.bin").writeBytes(ByteArray(900_000) { (it * 13).toByte() })
        val peerJson = """{"id":"${me.identity.id}","name":"Phone","pub":"${me.identity.pub}","address":"127.0.0.1","port":${me.port}}"""
        val p = node("send", TestSupport.tmp("node-id").path, TestSupport.freePort().toString(), TestSupport.freePort().toString(), peerJson, File(src, "Job 1").path)
        try {
            val sender = lineStarting(p, "SENDER ")
            val res = lineStarting(p, "RESULT ", 30000)
            assertEquals(true, field(res, "ok").asBoolean, res)
            assertEquals(setOf("Job 1/cut.svg", "Job 1/data.bin"), TestSupport.tree(inbox).keys)
            assertContentEquals(File(src, "Job 1/data.bin").readBytes(), File(inbox, "Job 1/data.bin").readBytes())
            assertEquals(1, asked.size)
            assertEquals("Node PC", asked[0].name)
            assertEquals(field(sender, "code").asString, asked[0].code)
            p.waitFor(5, TimeUnit.SECONDS)
        } finally { p.destroy(); me.stop() }
    }
}
