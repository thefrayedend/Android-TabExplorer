package com.tabexplorer.share

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

const val DEFAULT_PORT = 47591
const val GROUP = "239.255.47.59"
const val TAG = "tabexplorer-share"
const val ANNOUNCE_MS = 20_000L
const val ONLINE_MS = 65_000L
const val ASK_MS = 3 * 60 * 1000
const val CHUNK = 256 * 1024
const val MAX_ITEMS = 200_000
const val PART = ".txpart"

class ShareConfig(var enabled: Boolean, var name: String, var folder: File, var keepBoth: Boolean = true, var fat: Boolean = false)

class Peer(val id: String, val name: String, val pub: String, val address: String, val port: Int, val lastSeen: Long) {
    val code: String get() = Crypto.code(pub)
    fun online(now: Long = System.currentTimeMillis()) = now - lastSeen < ONLINE_MS
}

class AskRequest(val id: String, val name: String, val code: String, val count: Int, val bytes: Long)

/** One thing to send. Folders come before their contents; [rel] uses '/' and starts with the item's own name. */
class SendItem(val rel: String, val dir: Boolean, val size: Long = 0, val mtime: Long = 0, val open: (() -> InputStream)? = null)

class SendJob(val items: List<SendItem>) {
    @Volatile var canceled = false
    @Volatile var phase = ""
    @Volatile var doneBytes = 0L
    @Volatile var doneFiles = 0
    @Volatile var current = ""
    val totalFiles = items.count { !it.dir }
    val totalBytes = items.filter { !it.dir }.sumOf { it.size }
    val errors = ArrayList<Pair<String, String>>()
    @Volatile var sentCount = 0
    @Volatile var sentFolder = ""
    var onProgress: (SendJob) -> Unit = {}
}

class Received(val from: String, val folder: File, val saved: List<File>, val count: Int, val bytes: Long)

/**
 * TabExplorer sharing, as share.js does it: discovery over UDP, encrypted sends and receives over TCP.
 * No Android classes in here, so it is tested on a plain JVM against TabExplorer's own code.
 */
class Share(
    val identity: Identity,
    private val config: () -> ShareConfig,
    private val ask: (AskRequest) -> Boolean,
    private val log: (String) -> Unit = {},
    private val tcpPort: Int = DEFAULT_PORT,
    private val udpPort: Int = DEFAULT_PORT,
) {
    val peers = ConcurrentHashMap<String, Peer>()
    @Volatile var running = false; private set
    @Volatile var port = 0; private set
    @Volatile var error = ""; private set
    var connectTimeoutMs = 8000
    var onPeers: () -> Unit = {}
    var onReceived: (Received) -> Unit = {}
    /** Where UDP packets go; tests replace it. */
    var udpSend: (ByteArray, String) -> Unit = { b, addr -> realUdpSend(b, addr) }

    private var server: ServerSocket? = null
    private var udp: MulticastSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
    private var timer: Thread? = null

    init {
        for ((id, k) in identity.known) peers[id] = Peer(id, k.name, k.pub, k.address, k.port, 0)
    }

    val deviceName: String get() = Names.cleanName(config().name).ifEmpty { "Android" }
    val code: String get() = identity.code

    // ------------------------------------------------------------ on / off
    @Synchronized fun start() {
        if (running) return
        error = ""
        try {
            val ss = try { ServerSocket(tcpPort) } catch (e: java.net.BindException) { ServerSocket(0) }
            server = ss
            port = ss.localPort
            pool.execute { acceptLoop(ss) }
            val u = MulticastSocket(null)
            u.reuseAddress = true
            u.bind(InetSocketAddress(udpPort))
            try { u.broadcast = true; u.timeToLive = 1; u.loopbackMode = false } catch (_: Exception) { }
            udp = u
            join()
            running = true
            pool.execute { udpLoop(u) }
            log("share: on as \"$deviceName\" (code $code), files on TCP $port")
            announce("who")
            timer = Thread {
                try { while (running) { Thread.sleep(ANNOUNCE_MS); if (running) { join(); announce("here"); onPeers() } } } catch (_: InterruptedException) { }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            log("share: could not start: $error")
            stop(true)
        }
    }

    @Synchronized fun stop(quiet: Boolean = false) {
        if (running && !quiet) announce("bye")
        running = false
        timer?.interrupt(); timer = null
        try { udp?.close() } catch (_: Exception) { }
        udp = null
        try { server?.close() } catch (_: Exception) { }
        server = null
        if (!quiet) log("share: off")
    }

    // ------------------------------------------------------------ finding each other
    private fun interfaces(): List<java.net.InterfaceAddress> {
        val out = ArrayList<java.net.InterfaceAddress>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp) continue
                for (a in ni.interfaceAddresses) {
                    val ip = a.address
                    if (ip !is Inet4Address) continue
                    if (ip.hostAddress!!.startsWith("169.254.")) continue
                    out.add(a)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    private fun join() {
        val u = udp ?: return
        val g = InetAddress.getByName(GROUP)
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                if (ni.interfaceAddresses.none { it.address is Inet4Address }) continue
                try { u.joinGroup(InetSocketAddress(g, udpPort), ni) } catch (_: Exception) { /* joined already */ }
            }
        } catch (_: Exception) { }
    }

    fun message(t: String): ByteArray {
        val o = JsonObject()
        o.addProperty("tx", TAG); o.addProperty("v", 1); o.addProperty("t", t)
        o.addProperty("id", identity.id); o.addProperty("name", deviceName); o.addProperty("port", port)
        o.addProperty("pub", identity.pub); o.addProperty("kind", kind)
        return o.toString().toByteArray(Charsets.UTF_8)
    }
    /** "phone" or "tablet": extra field, ignored by TabExplorer 1.7.0 (see ANDROID-HANDOFF section 5). */
    var kind = "phone"

    fun announce(t: String = "here") {
        val m = message(t)
        for (a in interfaces()) {
            if (a.address.isLoopbackAddress) continue
            udpSend(m, "multicast:" + a.address.hostAddress)
            val b = a.broadcast
            if (b != null) udpSend(m, b.hostAddress!!)
        }
        udpSend(m, "255.255.255.255")
        for (p in peers.values) if (p.address.isNotEmpty() && p.id != identity.id) udpSend(m, p.address)
    }

    private fun realUdpSend(m: ByteArray, addr: String) {
        val u = udp ?: return
        try {
            if (addr.startsWith("multicast:")) {
                val ip = InetAddress.getByName(addr.removePrefix("multicast:"))
                val ni = NetworkInterface.getByInetAddress(ip) ?: return
                u.networkInterface = ni
                u.send(DatagramPacket(m, m.size, InetAddress.getByName(GROUP), udpPort))
            } else {
                u.send(DatagramPacket(m, m.size, InetAddress.getByName(addr), udpPort))
            }
        } catch (_: Exception) { }
    }

    private fun udpLoop(u: MulticastSocket) {
        val buf = ByteArray(4096)
        while (running && !u.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                u.receive(p)
                onPacket(p.data.copyOfRange(0, p.length), p.address.hostAddress ?: continue)
            } catch (_: Exception) { if (u.isClosed) break }
        }
    }

    private val idRe = Regex("^[0-9a-f]{8,64}$")

    /** A discovery packet arrived (public so tests can feed it). */
    fun onPacket(data: ByteArray, from: String) {
        val j = try { com.google.gson.JsonParser.parseString(String(data, Charsets.UTF_8)).asJsonObject } catch (_: Exception) { return }
        fun s(k: String) = j.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        if (s("tx") != TAG || j.get("v")?.takeIf { it.isJsonPrimitive }?.asInt != 1) return
        val id = s("id") ?: return
        if (id == identity.id || !idRe.matches(id)) return
        val t = s("t")
        if (t == "bye") { peers[id]?.let { peers[id] = Peer(it.id, it.name, it.pub, it.address, it.port, 0) }; onPeers(); return }
        val pub = s("pub") ?: return
        val pt = j.get("port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return
        if (pub.length > 200 || pt !in 1..65535 || Crypto.parsePub(pub) == null) return
        val p = Peer(id, Names.cleanName(s("name")).ifEmpty { "PC" }, pub, from, pt, System.currentTimeMillis())
        val before = peers[id]
        peers[id] = p
        remember(p)
        if (before == null || before.lastSeen == 0L || before.name != p.name || before.address != p.address) onPeers()
        if (t == "who") pool.execute { try { Thread.sleep(50L + Random.nextLong(250)) } catch (_: InterruptedException) { }; if (running) announce("here") }
    }

    /** Add a device by address (tests, QR pairing, networks that drop discovery). */
    fun addPeer(id: String, name: String, pub: String, address: String, port: Int) {
        val p = Peer(id, name, pub, address, port, System.currentTimeMillis())
        peers[id] = p; remember(p); onPeers()
    }

    private fun remember(p: Peer) {
        val k = identity.known[p.id]
        if (k != null && k.name == p.name && k.pub == p.pub && k.address == p.address && k.port == p.port) return
        identity.known[p.id] = Known(p.name, p.pub, p.address, p.port, if (k != null && k.pub == p.pub) k.sent else 0)
        identity.save()
    }

    fun setTrusted(id: String, on: Boolean): Boolean {
        if (on) { val p = peers[id] ?: return false; identity.trusted[id] = Trusted(p.name, p.pub, System.currentTimeMillis()) }
        else identity.trusted.remove(id)
        identity.save(); onPeers(); return true
    }
    fun forget(id: String) { identity.trusted.remove(id); identity.known.remove(id); peers.remove(id); identity.save(); onPeers() }

    /** Devices to show: on now, or approved, or sent to before. */
    fun visiblePeers(): List<Peer> {
        val now = System.currentTimeMillis()
        return peers.values.filter { it.id != identity.id && ((running && it.online(now)) || identity.trusted.containsKey(it.id) || (identity.known[it.id]?.sent ?: 0) > 0) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.code }))
    }

    // ------------------------------------------------------------ sending
    fun send(peerId: String, job: SendJob) {
        val peer = peers[peerId]
        if (peer == null || peer.address.isEmpty()) throw ShareException("That device hasn't been found on the network.")
        if (job.items.isEmpty()) return
        if (job.items.size > MAX_ITEMS) throw ShareException("That is too many files to send at once.")
        val name = peer.name
        val sock = Socket()
        val ch = Channel(sock)
        try {
            try { sock.connect(InetSocketAddress(peer.address, peer.port), connectTimeoutMs) } catch (e: java.net.SocketTimeoutException) {
                val seen = System.currentTimeMillis() - peer.lastSeen < ONLINE_MS
                if (seen) throw ShareException("$name is on the network, but didn't let TabExplorer in: its firewall is most likely blocking it. On $name, open Sharing and click \"Let other PCs in\".")
                throw ShareException("Couldn't reach $name: it didn't answer (is it on, with TabExplorer running?)")
            } catch (e: java.net.ConnectException) {
                throw ShareException("Couldn't reach $name: it isn't taking files right now (is TabExplorer open there, with sharing on?)")
            }
            sock.tcpNoDelay = true
            val nonceC = Crypto.random(16)
            ch.send(obj("t" to "hello", "v" to 1, "id" to identity.id, "name" to deviceName, "pub" to identity.pub, "nonce" to Crypto.hex(nonceC)))
            val hi = ch.json(10_000)
            if (hi.str("t") == "no") throw ShareException("$name isn't taking files: ${hi.str("message") ?: "sharing is off there"}.")
            if (hi.str("t") != "hello" || hi.str("id") != peer.id || hi.str("pub") != peer.pub)
                throw ShareException("The device that answered isn't the $name found on the network. Nothing was sent.")
            val pubRaw = Crypto.parsePub(peer.pub) ?: throw ShareException("Something unexpected arrived.")
            val nonceS = Crypto.hex(hi.str("nonce") ?: throw ShareException("Something unexpected arrived."))
            ch.setKeys(Crypto.sessionKeys(Crypto.sharedSecret(identity.privRaw, pubRaw), nonceC, nonceS), true)

            val items = JsonArray()
            for (it in job.items) items.add(JsonObject().also { o ->
                o.addProperty("rel", it.rel)
                if (it.dir) o.addProperty("dir", true) else { o.addProperty("size", it.size); o.addProperty("mtime", it.mtime.toDouble()) }
            })
            ch.send(JsonObject().also { o -> o.addProperty("t", "offer"); o.addProperty("count", job.totalFiles); o.addProperty("bytes", job.totalBytes); o.add("items", items) })
            job.phase = "waiting"; job.onProgress(job)
            val ans = ch.json(ASK_MS + 15_000)
            if (ans.str("t") == "no") throw ShareException("$name said no: ${ans.str("message") ?: "it didn't accept the files"}.")
            if (ans.str("t") != "go") throw ShareException("Something unexpected arrived.")
            job.phase = "copy"; job.onProgress(job)
            val buf = ByteArray(CHUNK)
            for ((i, it) in job.items.withIndex()) {
                if (it.dir) continue
                if (job.canceled) { ch.close(); return }
                job.current = it.rel; job.onProgress(job)
                ch.send(obj("t" to "file", "i" to i))
                var sent = 0L
                try {
                    val ins = it.open!!.invoke()
                    ins.use { s ->
                        while (true) {
                            if (job.canceled) { ch.close(); return }
                            val n = s.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            ch.sendData(buf, n)
                            sent += n; job.doneBytes += n; job.onProgress(job)
                        }
                    }
                    ch.send(obj("t" to "end", "i" to i, "size" to sent))
                } catch (e: ShareException) { throw e
                } catch (e: java.net.SocketException) { throw e
                } catch (e: Exception) {
                    job.errors.add(it.rel to (e.message ?: e.toString()))
                    ch.send(obj("t" to "skip", "i" to i))
                    job.doneBytes += maxOf(0L, it.size - sent)
                }
                job.doneFiles++
            }
            ch.send(obj("t" to "done"))
            val res = ch.json(60_000)
            if (res.str("t") != "saved") throw ShareException("Something unexpected arrived.")
            res.getAsJsonArray("errors")?.forEach { e -> val o = e.asJsonObject; job.errors.add((o.str("path") ?: "") to "$name: ${o.str("message")}") }
            job.sentCount = res.get("count")?.asInt ?: 0
            job.sentFolder = res.str("folder") ?: ""
            identity.known[peer.id]?.let { it.sent = System.currentTimeMillis(); identity.save() }
            log("share: sent ${job.sentCount} file(s) to $name")
        } catch (e: ShareException) {
            if (job.canceled) return
            log("share: sending to $name failed: ${e.message}")
            if (e.code == "EAUTH") throw ShareException("$name couldn't prove who it is. Nothing more was sent.")
            if (e.code == "ECONNRESET" || e.code == "TIMEOUT") throw ShareException("The connection to $name was cut off: ${if (e.code == "TIMEOUT") "it didn't answer (is it on, with TabExplorer running?)" else "the connection was cut off"}")
            throw e
        } catch (e: java.net.SocketException) {
            if (job.canceled) return
            throw ShareException("The connection to $name was cut off: the connection was cut off")
        } finally { ch.close() }
    }

    // ------------------------------------------------------------ receiving
    private fun acceptLoop(ss: ServerSocket) {
        while (running && !ss.isClosed) {
            try {
                val s = ss.accept()
                pool.execute { try { receive(s) } catch (e: Exception) { log("share: receiving failed: ${e.message}"); try { s.close() } catch (_: Exception) { } } }
            } catch (_: Exception) { if (ss.isClosed) break }
        }
    }

    private fun receive(sock: Socket) {
        sock.tcpNoDelay = true
        val ch = Channel(sock)
        val c = config()
        val hi = ch.json(10_000)
        val hiId = hi.str("id"); val hiPub = hi.str("pub"); val hiNonce = hi.str("nonce")
        if (hi.str("t") != "hello" || hi.get("v")?.asInt != 1 || hiId == null || hiPub == null || hiNonce == null || !Regex("^[0-9a-f]{32}$").matches(hiNonce)) { ch.close(); return }
        if (!c.enabled || !running) { ch.send(obj("t" to "no", "message" to "sharing is off there")); ch.close(); return }
        val pubRaw = Crypto.parsePub(hiPub) ?: run { ch.close(); return }
        val nonceS = Crypto.random(16)
        ch.send(obj("t" to "hello", "v" to 1, "id" to identity.id, "name" to deviceName, "pub" to identity.pub, "nonce" to Crypto.hex(nonceS)))
        ch.setKeys(Crypto.sessionKeys(Crypto.sharedSecret(identity.privRaw, pubRaw), Crypto.hex(hiNonce), nonceS), false)
        val from = Names.cleanName(hi.str("name")).ifEmpty { "Another device" }
        val offer = ch.json(20_000)
        val itemsJson = offer.getAsJsonArray("items")
        if (offer.str("t") != "offer" || itemsJson == null || itemsJson.size() > MAX_ITEMS) { ch.close(); return }
        val seen = peers[hiId]
        if (seen == null || seen.pub != hiPub) {
            val p = Peer(hiId, from, hiPub, sock.inetAddress.hostAddress ?: "", seen?.port ?: DEFAULT_PORT, System.currentTimeMillis())
            peers[hiId] = p; remember(p); onPeers()
        }
        class It(val rel: String, val dir: Boolean, val size: Long, val mtime: Double)
        val items = itemsJson.map { e -> val o = e.asJsonObject
            It(o.str("rel") ?: "", o.get("dir")?.asBoolean ?: false, o.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L, o.get("mtime")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0) }
        val count = items.count { !it.dir }
        val bytes = items.sumOf { if (it.dir) 0L else maxOf(0L, it.size) }
        if (!identity.isTrusted(hiId, hiPub)) {
            log("share: $from (code ${Crypto.code(hiPub)}) asks to send $count file(s)")
            val answer = java.util.concurrent.CompletableFuture<Boolean>()
            pool.execute { try { answer.complete(ask(AskRequest(hiId, from, Crypto.code(hiPub), count, bytes))) } catch (_: Exception) { answer.complete(false) } }
            val yes = try { answer.get(ASK_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { false }
            if (!yes) { try { ch.send(obj("t" to "no", "message" to "nobody there accepted the files")) } catch (_: Exception) { }; ch.close(); return }
            identity.trusted[hiId] = Trusted(from, hiPub, System.currentTimeMillis()); identity.save(); onPeers()
        }
        val folder = c.folder
        try {
            folder.mkdirs()
            if (!folder.isDirectory) throw ShareException("its folder for files sent to it is missing")
            if (folder.usableSpace < bytes + 16L * 1024 * 1024) throw ShareException("there isn't enough room on its disk")
        } catch (e: Exception) {
            try { ch.send(obj("t" to "no", "message" to (e.message ?: "can't save there"))) } catch (_: Exception) { }
            ch.close(); return
        }
        ch.send(obj("t" to "go"))
        val root = folder.canonicalFile
        val replace = !c.keepBoth
        val fat = c.fat
        val errors = ArrayList<Pair<String, String>>()
        val top = HashMap<String, String>()
        val saved = ArrayList<File>()
        var received = 0
        fun target(rel: String, isDir: Boolean): File? {
            val parts = Names.safeRel(rel, fat) ?: return null
            var first = top[parts[0]]
            if (first == null) {
                val f = File(root, parts[0])
                val whole = parts.size == 1
                first = when {
                    !f.exists() -> parts[0]
                    f.isDirectory && (isDir || !whole) -> parts[0]
                    !f.isDirectory && whole && !isDir && replace -> parts[0]
                    else -> Names.uniqueName({ File(root, it).exists() }, parts[0])
                }
                top[parts[0]] = first
                saved.add(File(root, first))
            }
            var out = File(root, first)
            for (p in parts.drop(1)) out = File(out, p)
            val res = out.canonicalFile
            if (res != root && !res.path.startsWith(root.path + File.separator)) return null
            return res
        }
        class Cur(val i: Int, val dest: File?, val part: File?, val out: java.io.OutputStream?, var got: Long = 0, var failed: Exception? = null)
        var cur: Cur? = null
        fun closeCur(keep: Boolean) {
            val c0 = cur ?: return
            cur = null
            try { c0.out?.close() } catch (_: Exception) { }
            val part = c0.part ?: return
            if (!keep) { part.delete(); return }
            try {
                var dest = c0.dest!!
                if (dest.isDirectory || (dest.exists() && !replace)) dest = File(dest.parentFile, Names.uniqueName({ File(dest.parentFile, it).exists() }, dest.name))
                else if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) { part.copyTo(dest, true); part.delete() }
                val m = items[c0.i].mtime
                if (m > 0) dest.setLastModified(m.toLong())
                received++
            } catch (e: Exception) {
                errors.add(items[c0.i].rel to (e.message ?: e.toString()))
                part.delete()
            }
        }
        try {
            for (it in items) {
                if (!it.dir) continue
                val d = target(it.rel, true)
                if (d == null) { errors.add(it.rel to "a name that can't be used here"); continue }
                if (!d.mkdirs() && !d.isDirectory) errors.add(it.rel to "couldn't make the folder")
            }
            while (true) {
                val m = ch.next(5 * 60 * 1000)
                if (m.data != null) {
                    val cc = cur
                    if (cc != null && cc.out != null) {
                        cc.got += m.data.size
                        if (cc.failed == null) try { cc.out.write(m.data) } catch (e: Exception) { cc.failed = e }
                    } else if (cc != null) cc.got += m.data.size
                    continue
                }
                val j = m.json!!
                when (j.str("t")) {
                    "file" -> {
                        closeCur(false)
                        val i = j.get("i")?.asInt ?: throw ShareException("Something unexpected arrived.")
                        val it = items.getOrNull(i)
                        if (it == null || it.dir) throw ShareException("Something unexpected arrived.")
                        val dest = target(it.rel, false)
                        if (dest == null) { errors.add(it.rel to "a name that can't be used here"); cur = Cur(i, null, null, null); continue }
                        dest.parentFile!!.mkdirs()
                        val part = File(dest.parentFile, dest.name + "." + Crypto.hex(Crypto.random(3)) + PART)
                        cur = Cur(i, dest, part, part.outputStream().buffered(64 * 1024))
                    }
                    "end" -> {
                        val i = j.get("i")?.asInt
                        val cc = cur
                        if (cc == null || cc.i != i) throw ShareException("Something unexpected arrived.")
                        if (cc.part == null) { cur = null; continue }
                        val f = cc.failed
                        if (f != null) { errors.add(items[cc.i].rel to (f.message ?: f.toString())); closeCur(false); continue }
                        closeCur(cc.got == (j.get("size")?.asLong ?: -1L))
                    }
                    "skip" -> closeCur(false)
                    "done" -> { closeCur(false); break }
                    else -> throw ShareException("Something unexpected arrived.")
                }
            }
            val errArr = JsonArray()
            errors.take(200).forEach { (p, m) -> errArr.add(obj("path" to p, "message" to m)) }
            ch.send(JsonObject().also { o -> o.addProperty("t", "saved"); o.addProperty("count", received); o.addProperty("folder", root.path); o.add("errors", errArr) })
            try { sock.shutdownOutput() } catch (_: Exception) { }
            log("share: received $received file(s) from $from into ${root.path}")
        } catch (e: Exception) {
            closeCur(false)
            log("share: receiving from $from stopped: ${e.message}")
            ch.close()
        }
        if (received > 0 || saved.isNotEmpty()) onReceived(Received(from, root, saved.filter { it.exists() }, received, bytes))
    }

    // ------------------------------------------------------------ small JSON helpers
    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun obj(vararg kv: Pair<String, Any>): JsonObject {
        val o = JsonObject()
        for ((k, v) in kv) when (v) { is String -> o.addProperty(k, v); is Int -> o.addProperty(k, v); is Long -> o.addProperty(k, v); is Boolean -> o.addProperty(k, v); else -> error("bad") }
        return o
    }
}
