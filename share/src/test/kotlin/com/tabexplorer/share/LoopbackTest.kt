package com.tabexplorer.share

import com.google.gson.JsonObject
import java.io.File
import java.net.Socket
import kotlin.test.*

/** Two instances of our own sharing talking to each other over loopback TCP (no discovery needed). */
class LoopbackTest {
    private fun pair(keepBoth: Boolean = true, ask: (AskRequest) -> Boolean = { true }): Triple<Share, Share, File> {
        val inbox = TestSupport.tmp("inbox")
        val a = TestSupport.share(TestSupport.tmp("a"), TestSupport.tmp("a-in"), "Phone")
        val b = TestSupport.share(TestSupport.tmp("b"), inbox, "Office", keepBoth, ask)
        a.start(); b.start()
        a.addPeer(b.identity.id, "Office", b.identity.pub, "127.0.0.1", b.port)
        return Triple(a, b, inbox)
    }

    @Test fun sendsFilesAndFolders() {
        val (a, b, inbox) = pair()
        val src = TestSupport.tmp("src")
        File(src, "Job 1/sub").mkdirs()
        File(src, "Job 1/cut.svg").writeText("<svg/>")
        File(src, "Job 1/sub/big.bin").writeBytes(ByteArray(1_000_000) { (it % 251).toByte() })
        File(src, "Job 1/empty").mkdirs()
        val big = File(src, "Job 1/sub/big.bin"); big.setLastModified(1_700_000_000_000L)
        val job = SendJob(TestSupport.itemsOf(File(src, "Job 1")))
        a.send(b.identity.id, job)
        assertEquals(2, job.sentCount)
        assertEquals(setOf("Job 1/cut.svg", "Job 1/sub/big.bin"), TestSupport.tree(inbox).keys)
        assertContentEquals(big.readBytes(), File(inbox, "Job 1/sub/big.bin").readBytes())
        assertTrue(File(inbox, "Job 1/empty").isDirectory)
        assertEquals(1_700_000_000_000L, File(inbox, "Job 1/sub/big.bin").lastModified())
        assertTrue(inbox.walkTopDown().none { it.name.endsWith(PART) })
        a.stop(); b.stop()
    }

    @Test fun firstSendAsksOnceThenRemembersTheKey() {
        var asked = 0
        val (a, b, inbox) = pair(ask = { asked++; true })
        val f = File(TestSupport.tmp("s"), "one.txt").also { it.writeText("1") }
        a.send(b.identity.id, SendJob(TestSupport.itemsOf(f)))
        a.send(b.identity.id, SendJob(TestSupport.itemsOf(f)))
        assertEquals(1, asked)
        assertTrue(b.identity.isTrusted(a.identity.id, a.identity.pub))
        a.stop(); b.stop()
    }

    @Test fun refusalIsReportedAndNothingIsWritten() {
        val (a, b, inbox) = pair(ask = { false })
        val f = File(TestSupport.tmp("s"), "one.txt").also { it.writeText("1") }
        val e = assertFailsWith<ShareException> { a.send(b.identity.id, SendJob(TestSupport.itemsOf(f))) }
        assertTrue(e.message!!.contains("said no"), e.message)
        assertTrue(TestSupport.tree(inbox).isEmpty())
        a.stop(); b.stop()
    }

    @Test fun sameNameKeepsBothOrReplaces() {
        for (keepBoth in listOf(true, false)) {
            val (a, b, inbox) = pair(keepBoth)
            File(inbox, "one.txt").writeText("old")
            val f = File(TestSupport.tmp("s"), "one.txt").also { it.writeText("new") }
            a.send(b.identity.id, SendJob(TestSupport.itemsOf(f)))
            if (keepBoth) assertEquals(mapOf("one.txt" to "old", "one (2).txt" to "new"), TestSupport.tree(inbox))
            else assertEquals(mapOf("one.txt" to "new"), TestSupport.tree(inbox))
            a.stop(); b.stop()
        }
    }

    @Test fun aFileNeverReplacesAFolderAndFoldersMerge() {
        val (a, b, inbox) = pair(false)
        File(inbox, "x").mkdirs(); File(inbox, "x/keep.txt").writeText("k")
        File(inbox, "Docs").mkdirs(); File(inbox, "Docs/old.txt").writeText("o")
        val s = TestSupport.tmp("s")
        File(s, "x.tmp").writeText("file named like a folder")
        val named = File(TestSupport.tmp("s2"), "x").also { it.writeText("file") }
        a.send(b.identity.id, SendJob(TestSupport.itemsOf(named)))
        File(s, "Docs").mkdirs(); File(s, "Docs/new.txt").writeText("n")
        a.send(b.identity.id, SendJob(TestSupport.itemsOf(File(s, "Docs"))))
        val t = TestSupport.tree(inbox)
        assertEquals("k", t["x/keep.txt"]); assertEquals("file", t["x (2)"])
        assertEquals("o", t["Docs/old.txt"]); assertEquals("n", t["Docs/new.txt"])
        a.stop(); b.stop()
    }

    @Test fun unreadableFileIsSkippedAndReported() {
        val (a, b, inbox) = pair()
        val good = File(TestSupport.tmp("s"), "good.txt").also { it.writeText("g") }
        val items = listOf(SendItem("bad.txt", false, 10, 0) { throw java.io.IOException("can't read") }, TestSupport.itemsOf(good)[0])
        val job = SendJob(items)
        a.send(b.identity.id, job)
        assertEquals(setOf("good.txt"), TestSupport.tree(inbox).keys)
        assertEquals("bad.txt", job.errors.single().first)
        a.stop(); b.stop()
    }

    /** A hostile sender: handshake properly (so it is "approved"), then offer names that try to leave the folder. */
    @Test fun namesThatEscapeTheFolderAreRefused() {
        val (a, b, inbox) = pair()
        // make a trusted
        b.identity.trusted[a.identity.id] = Trusted("Phone", a.identity.pub, 0)
        val sock = Socket("127.0.0.1", b.port); val ch = Channel(sock)
        val nonceC = Crypto.random(16)
        ch.send(JsonObject().apply { addProperty("t", "hello"); addProperty("v", 1); addProperty("id", a.identity.id); addProperty("name", "Phone"); addProperty("pub", a.identity.pub); addProperty("nonce", Crypto.hex(nonceC)) })
        val hi = ch.json(5000)
        ch.setKeys(Crypto.sessionKeys(Crypto.sharedSecret(a.identity.privRaw, Crypto.parsePub(b.identity.pub)!!), nonceC, Crypto.hex(hi.get("nonce").asString)), true)
        val items = com.google.gson.JsonArray()
        for (rel in listOf("../evil.txt", "ok/../../evil2.txt", "good.txt")) items.add(JsonObject().apply { addProperty("rel", rel); addProperty("size", 1); addProperty("mtime", 0.0) })
        ch.send(JsonObject().apply { addProperty("t", "offer"); addProperty("count", 3); addProperty("bytes", 3); add("items", items) })
        assertEquals("go", ch.json(5000).get("t").asString)
        for (i in 0..2) {
            ch.send(JsonObject().apply { addProperty("t", "file"); addProperty("i", i) })
            ch.sendData("x".toByteArray(), 1)
            ch.send(JsonObject().apply { addProperty("t", "end"); addProperty("i", i); addProperty("size", 1) })
        }
        ch.send(JsonObject().apply { addProperty("t", "done") })
        val res = ch.json(5000)
        assertEquals(1, res.get("count").asInt)
        assertEquals(2, res.getAsJsonArray("errors").size())
        assertEquals(setOf("good.txt"), TestSupport.tree(inbox).keys)
        assertFalse(File(inbox.parentFile, "evil.txt").exists())
        ch.close(); a.stop(); b.stop()
    }

    @Test fun anImpostorKeyIsRefused() {
        val (a, b, _) = pair()
        // the peer's id is known but a different device answers with another key: nothing is sent
        val other = TestSupport.share(TestSupport.tmp("o"), TestSupport.tmp("o-in"), "Evil")
        other.start()
        a.addPeer(b.identity.id, "Office", b.identity.pub, "127.0.0.1", other.port)
        val f = File(TestSupport.tmp("s"), "one.txt").also { it.writeText("1") }
        val e = assertFailsWith<ShareException> { a.send(b.identity.id, SendJob(TestSupport.itemsOf(f))) }
        assertTrue(e.message!!.contains("isn't the"), e.message)
        a.stop(); b.stop(); other.stop()
    }

    @Test fun sharingOffRefusesPlainly() {
        val a = TestSupport.share(TestSupport.tmp("a"), TestSupport.tmp("a-in"), "Phone")
        val inbox = TestSupport.tmp("inbox")
        val b = Share(Identity.load(File(TestSupport.tmp("b"), "share.json")), { ShareConfig(false, "Office", inbox) }, { true }, {}, TestSupport.freePort(), TestSupport.freePort())
        a.start(); b.start()
        a.addPeer(b.identity.id, "Office", b.identity.pub, "127.0.0.1", b.port)
        val f = File(TestSupport.tmp("s"), "one.txt").also { it.writeText("1") }
        val e = assertFailsWith<ShareException> { a.send(b.identity.id, SendJob(TestSupport.itemsOf(f))) }
        assertTrue(e.message!!.contains("isn't taking files"), e.message)
        a.stop(); b.stop()
    }

    @Test fun discoveryPacketsAreParsedLikeTabExplorersDo() {
        val a = TestSupport.share(TestSupport.tmp("a"), TestSupport.tmp("a-in"), "Phone")
        val kp = Crypto.generateKeyPair()
        fun pkt(id: String, pub: String, port: Int, t: String = "here") = """{"tx":"tabexplorer-share","v":1,"t":"$t","id":"$id","name":"Office\u0001","port":$port,"pub":"$pub","extra":1}""".toByteArray()
        val id = "0123456789abcdef01234567"
        a.onPacket(pkt(id, kp.pubText, 47591), "192.168.1.20")
        val p = a.peers[id]!!
        assertEquals("Office", p.name); assertEquals("192.168.1.20", p.address); assertEquals(47591, p.port); assertTrue(p.online())
        a.onPacket(pkt("zz", kp.pubText, 47591), "1.1.1.1")              // bad id
        a.onPacket(pkt("abcdef012345", "AAAA", 47591), "1.1.1.1")        // bad key
        a.onPacket(pkt("abcdef012345", kp.pubText, 0), "1.1.1.1")        // bad port
        a.onPacket(pkt(a.identity.id, kp.pubText, 47591), "1.1.1.1")     // ourselves
        a.onPacket("junk".toByteArray(), "1.1.1.1")
        assertEquals(setOf(id), a.peers.keys)
        a.onPacket(pkt(id, kp.pubText, 47591, "bye"), "192.168.1.20")
        assertFalse(a.peers[id]!!.online())
    }

    @Test fun ourAnnouncementHasTheFieldsTabExplorerReads() {
        val a = TestSupport.share(TestSupport.tmp("a"), TestSupport.tmp("a-in"), "Phone")
        a.start()
        val j = com.google.gson.JsonParser.parseString(String(a.message("here"))).asJsonObject
        assertEquals("tabexplorer-share", j.get("tx").asString); assertEquals(1, j.get("v").asInt)
        assertEquals(a.identity.id, j.get("id").asString); assertEquals(24, a.identity.id.length)
        assertEquals(a.port, j.get("port").asInt); assertNotNull(Crypto.parsePub(j.get("pub").asString))
        a.stop()
    }
}
