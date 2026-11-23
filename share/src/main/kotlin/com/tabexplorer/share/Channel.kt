package com.tabexplorer.share

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val MAX_FRAME = 32 * 1024 * 1024

class ShareException(message: String, val code: String = "") : IOException(message)

/** One message: either JSON or a chunk of file data. */
class Message(val json: JsonObject?, val data: ByteArray?)

/**
 * Length-prefixed frames over a socket. The greeting is plain JSON; after [setKeys] every frame body is
 * AES-256-GCM (12-byte IV: 4 zero bytes then a big-endian 64-bit counter per direction, 16-byte tag at the end)
 * of one kind byte (1 = JSON, 2 = file data) and the payload.
 */
class Channel(private val sock: Socket) {
    private val input = DataInputStream(sock.getInputStream().buffered(64 * 1024))
    private val output: OutputStream = sock.getOutputStream().buffered(64 * 1024)
    private var sendKey: SecretKeySpec? = null
    private var recvKey: SecretKeySpec? = null
    private var sendN = 0L
    private var recvN = 0L

    fun setKeys(k: Crypto.SessionKeys, isClient: Boolean) {
        sendKey = SecretKeySpec(if (isClient) k.clientToServer else k.serverToClient, "AES")
        recvKey = SecretKeySpec(if (isClient) k.serverToClient else k.clientToServer, "AES")
    }

    private fun iv(n: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(n).array()

    private fun rawNext(timeoutMs: Int): ByteArray {
        sock.soTimeout = timeoutMs
        try {
            val n = input.readInt()
            if (n < 0 || n > MAX_FRAME) throw ShareException("The other device sent something that isn't TabExplorer sharing.")
            val body = ByteArray(n)
            input.readFully(body)
            return body
        } catch (e: SocketTimeoutException) {
            throw ShareException("timed out", "TIMEOUT")
        } catch (e: java.io.EOFException) {
            throw ShareException("The connection was cut off.", "ECONNRESET")
        }
    }

    fun next(timeoutMs: Int): Message {
        val body = rawNext(timeoutMs)
        val key = recvKey ?: return Message(parse(body), null)
        if (body.size < 17) throw ShareException("A damaged message arrived.")
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(recvN++)))
            c.doFinal(body)
        } catch (e: Exception) {
            throw ShareException("The other device couldn't prove who it is.", "EAUTH")
        }
        if (plain.isEmpty()) throw ShareException("A damaged message arrived.")
        return if (plain[0].toInt() == 1) Message(parse(plain.copyOfRange(1, plain.size)), null)
        else Message(null, plain.copyOfRange(1, plain.size))
    }

    fun json(timeoutMs: Int): JsonObject =
        next(timeoutMs).json ?: throw ShareException("Something unexpected arrived.")

    private fun parse(b: ByteArray): JsonObject = try {
        JsonParser.parseString(String(b, Charsets.UTF_8)).asJsonObject
    } catch (e: Exception) { throw ShareException("The other device sent something that isn't TabExplorer sharing.") }

    private fun frame(body: ByteArray) {
        output.write(ByteBuffer.allocate(4).putInt(body.size).array())
        output.write(body)
        output.flush()
    }

    private fun seal(kind: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size) {
        val key = sendKey
        if (key == null) { frame(payload.copyOfRange(off, off + len)); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(sendN++)))
        c.updateAAD(ByteArray(0))
        val head = c.update(byteArrayOf(kind.toByte()))
        val mid = c.update(payload, off, len)
        val tail = c.doFinal()
        frame((head ?: ByteArray(0)) + (mid ?: ByteArray(0)) + tail)
    }

    fun send(obj: JsonObject) = seal(1, obj.toString().toByteArray(Charsets.UTF_8))
    fun sendData(buf: ByteArray, len: Int) = seal(2, buf, 0, len)
    fun close() { try { sock.close() } catch (_: Exception) {} }
}
