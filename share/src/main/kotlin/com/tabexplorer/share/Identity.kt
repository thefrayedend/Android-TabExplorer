package com.tabexplorer.share

import com.google.gson.Gson
import java.io.File

class Known(var name: String = "", var pub: String = "", var address: String = "", var port: Int = 0, var sent: Long = 0)
class Trusted(var name: String = "", var pub: String = "", var since: Long = 0)

/** This device's identity and who it knows and trusts, kept in one file. Same idea as TabExplorer's share.json. */
class Identity private constructor(private val file: File?, var id: String, var priv: String, var pub: String) {
    val trusted: MutableMap<String, Trusted> = LinkedHashMap()
    val known: MutableMap<String, Known> = LinkedHashMap()

    private class Disk(var id: String = "", var priv: String = "", var pub: String = "",
                       var trusted: Map<String, Trusted> = emptyMap(), var known: Map<String, Known> = emptyMap())

    val privRaw: ByteArray get() = Crypto.unb64(priv)
    val code: String get() = Crypto.code(pub)

    @Synchronized fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Gson().toJson(Disk(id, priv, pub, trusted, known)))
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun isTrusted(id: String, pub: String): Boolean = trusted[id]?.pub == pub

    companion object {
        /** Loads [file], or makes a new identity (12 random bytes as hex id, X25519 key pair) and saves it. */
        fun load(file: File?): Identity {
            if (file != null && file.exists()) {
                try {
                    val d = Gson().fromJson(file.readText(), Disk::class.java)
                    if (d != null && d.id.isNotEmpty() && d.priv.isNotEmpty() && d.pub.isNotEmpty()) {
                        return Identity(file, d.id, d.priv, d.pub).also { it.trusted.putAll(d.trusted); it.known.putAll(d.known) }
                    }
                } catch (_: Exception) { }
            }
            val kp = Crypto.generateKeyPair()
            return Identity(file, Crypto.hex(Crypto.random(12)), Crypto.b64(kp.priv), kp.pubText).also { it.save() }
        }

        /** An identity from fixed keys (tests). */
        fun fixed(id: String, priv: ByteArray): Identity =
            Identity(null, id, Crypto.b64(priv), Crypto.keyPairFromPrivate(priv).pubText)
    }
}
