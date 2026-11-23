package com.tabexplorer.share

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Everything cryptographic that TabExplorer's share.js does, byte for byte. */
object Crypto {
    private val rng = SecureRandom()
    /** DER prefix of an X25519 SubjectPublicKeyInfo: 12 bytes, then the 32-byte key. */
    private val SPKI_PREFIX = hex("302a300506032b656e032100")
    const val INFO = "tabexplorer-share v1"

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    class KeyPair(val priv: ByteArray, val pub: ByteArray) {
        /** The public key as it travels: base64 of its SPKI DER encoding. */
        val pubText: String get() = spki(pub)
    }

    fun generateKeyPair(): KeyPair {
        val p = X25519PrivateKeyParameters(rng)
        return KeyPair(p.encoded, p.generatePublicKey().encoded)
    }
    fun keyPairFromPrivate(priv: ByteArray): KeyPair =
        KeyPair(priv, X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded)

    fun spki(raw: ByteArray): String = b64(SPKI_PREFIX + raw)

    /** The raw 32-byte key out of a travelling public key, or null if it isn't a valid X25519 one. */
    fun parsePub(text: String): ByteArray? {
        if (text.length > 200) return null
        val der = try { unb64(text) } catch (_: Exception) { return null }
        if (der.size != 44) return null
        if (!der.copyOfRange(0, 12).contentEquals(SPKI_PREFIX)) return null
        return der.copyOfRange(12, 44)
    }

    /** The 8 digits shown beside a device's name, as "1234 5678". */
    fun code(pubText: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(pubText.toByteArray(Charsets.UTF_8))
        val n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
        val s = (n % 100000000L).toString().padStart(8, '0')
        return s.substring(0, 4) + " " + s.substring(4)
    }

    fun sharedSecret(myPriv: ByteArray, peerPubRaw: ByteArray): ByteArray {
        val ag = X25519Agreement()
        ag.init(X25519PrivateKeyParameters(myPriv, 0))
        val out = ByteArray(ag.agreementSize)
        ag.calculateAgreement(X25519PublicKeyParameters(peerPubRaw, 0), out, 0)
        return out
    }

    class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray)

    /** HKDF-SHA256(secret, salt = clientNonce + serverNonce, info), 64 bytes split in two. */
    fun sessionKeys(secret: ByteArray, nonceClient: ByteArray, nonceServer: ByteArray): SessionKeys {
        val g = HKDFBytesGenerator(SHA256Digest())
        g.init(HKDFParameters(secret, nonceClient + nonceServer, INFO.toByteArray(Charsets.UTF_8)))
        val okm = ByteArray(64)
        g.generateBytes(okm, 0, 64)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }
}
