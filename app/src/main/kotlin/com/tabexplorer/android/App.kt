package com.tabexplorer.android

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.tabexplorer.core.RecycleBin
import com.tabexplorer.share.AskRequest
import com.tabexplorer.share.Identity
import com.tabexplorer.share.Share
import com.tabexplorer.share.ShareConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.CompletableFuture

class Incoming(val req: AskRequest, val future: CompletableFuture<Boolean>)

class App : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
    lateinit var bin: RecycleBin
    lateinit var share: Share
    private var visible = false
    private var multicast: WifiManager.MulticastLock? = null

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    fun say(s: String) { _toast.value = s }
    fun toastShown() { _toast.value = null }
    /** Bumped when devices on the network change, so screens redraw. */
    private val _peers = MutableStateFlow(0)
    val peersVersion: StateFlow<Int> = _peers

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        OpService.createChannel(this)
        bin = RecycleBin(File(getExternalFilesDir(null) ?: filesDir, "bin"))
        Thread { runCatching { bin.purgeOld() } }.start()
        val tablet = resources.configuration.smallestScreenWidthDp >= 600
        share = Share(Identity.load(File(filesDir, "share.json")),
            { ShareConfig(true, prefs.deviceName, Storage.receiveFolder, prefs.keepBoth, false) }, ::askUser, { android.util.Log.i("share", it) })
        share.kind = if (tablet) "tablet" else "phone"
        share.onPeers = { _peers.value++ }
        share.onReceived = { r ->
            val files = r.saved.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
            if (files.isNotEmpty()) android.media.MediaScannerConnection.scanFile(this, files.take(500).map { it.path }.toTypedArray(), null, null)
            Ops.bump()
            say("Received ${r.count} file(s) from ${r.from}")
            notify(NOTE_GOT, "Received ${r.count} file(s) from ${r.from}", "In Downloads/TabExplorer", false)
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { visible = true; refreshSharing() }
            override fun onStop(owner: LifecycleOwner) { visible = false; refreshSharing() }
        })
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()

    /** Sharing is on while the app is open, or whenever "Ready to receive" is on (then a service keeps it alive). */
    @Synchronized fun refreshSharing() {
        val want = visible || prefs.ready
        if (want && !share.running) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicast = wm.createMulticastLock("tabexplorer").also { it.setReferenceCounted(false); it.acquire() }
            Thread { share.start() }.start()
        } else if (!want && share.running) {
            share.stop(); multicast?.takeIf { it.isHeld }?.release()
        }
        val svc = Intent(this, ShareService::class.java)
        if (prefs.ready && visible) ContextCompat.startForegroundService(this, svc) else if (!prefs.ready) stopService(svc)
    }

    private fun askUser(req: AskRequest): Boolean {
        val inc = Incoming(req, CompletableFuture())
        _incoming.value = inc
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("${req.name} wants to send you ${req.count} file(s)").setContentText("Code ${req.code}. Tap to answer.").setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(NOTE_ASK, n)
        val yes = try { inc.future.get() } catch (_: Exception) { false }
        getSystemService(NotificationManager::class.java).cancel(NOTE_ASK)
        if (_incoming.value === inc) _incoming.value = null
        return yes
    }
    fun answer(inc: Incoming, yes: Boolean) { inc.future.complete(yes); if (_incoming.value === inc) _incoming.value = null }

    fun notify(id: Int, title: String, text: String, ongoing: Boolean) {
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(id, NotificationCompat.Builder(this, "alerts").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(!ongoing).build())
    }

    companion object {
        lateinit var instance: App
        const val NOTE_ASK = OpService.NOTE_ASK; const val NOTE_GOT = OpService.NOTE_GOT
    }
}
