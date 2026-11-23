package com.tabexplorer.share

import com.google.gson.JsonParser
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Fixed-key values computed with TabExplorer's own share.js crypto (tools/interop/vectors.js). */
class VectorsTest {
    private val v = JsonParser.parseString(javaClass.getResource("/vectors.json")!!.readText()).asJsonObject
    private fun s(k: String) = v.get(k).asString

    @Test fun publicKeyTravelsAsSpkiBase64() {
        assertEquals(s("pubA"), Crypto.keyPairFromPrivate(Crypto.hex(s("privA"))).pubText)
        assertEquals(s("pubB"), Crypto.keyPairFromPrivate(Crypto.hex(s("privB"))).pubText)
    }

    @Test fun codeIsFromTheBase64TextOfTheKey() {
        assertEquals(s("codeA"), Crypto.code(s("pubA")))
        assertEquals(s("codeB"), Crypto.code(s("pubB")))
    }

    @Test fun sharedSecretMatchesBothWays() {
        val a = Crypto.sharedSecret(Crypto.hex(s("privA")), Crypto.parsePub(s("pubB"))!!)
        val b = Crypto.sharedSecret(Crypto.hex(s("privB")), Crypto.parsePub(s("pubA"))!!)
        assertEquals(s("secret"), Crypto.hex(a))
        assertContentEquals(a, b)
    }

    @Test fun sessionKeysMatchHkdf() {
        val k = Crypto.sessionKeys(Crypto.hex(s("secret")), Crypto.hex(s("nonceC")), Crypto.hex(s("nonceS")))
        assertEquals(s("c2s"), Crypto.hex(k.clientToServer))
        assertEquals(s("s2c"), Crypto.hex(k.serverToClient))
    }

    @Test fun badKeysAreRefused() {
        assertNull(Crypto.parsePub("not base64!!"))
        assertNull(Crypto.parsePub(Crypto.b64(ByteArray(44))))
        assertNull(Crypto.parsePub(Crypto.b64(ByteArray(32))))
        assertNull(Crypto.parsePub("A".repeat(300)))
        assertNotNull(Crypto.parsePub(s("pubA")))
    }

    /** A frame sealed by share.js (counter 0, then 1) opens in the Channel on the receiving side. */
    @Test fun channelOpensFramesSealedByShareJs() {
        val ss = ServerSocket(0)
        val t = Thread {
            val c = ss.accept()
            val out = c.getOutputStream()
            for (h in listOf(s("sealedJson0"), s("sealedData1"))) {
                val body = Crypto.hex(h)
                out.write(ByteBuffer.allocate(4).putInt(body.size).array()); out.write(body)
            }
            out.flush(); Thread.sleep(500); c.close()
        }
        t.start()
        val ch = Channel(Socket("127.0.0.1", ss.localPort))
        // the frames were sealed with the client-to-server key: we play the server, which receives with it
        ch.setKeys(Crypto.sessionKeys(Crypto.hex(s("secret")), Crypto.hex(s("nonceC")), Crypto.hex(s("nonceS"))), false)
        val m1 = ch.next(3000)
        assertEquals(s("json"), m1.json.toString())
        val m2 = ch.next(3000)
        assertEquals("hello file data", String(m2.data!!))
        ch.close(); ss.close()
    }
}
