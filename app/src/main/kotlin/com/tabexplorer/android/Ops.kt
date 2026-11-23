package com.tabexplorer.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tabexplorer.core.Conflict
import com.tabexplorer.core.FileOps
import com.tabexplorer.core.OpProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.CompletableFuture

class OpState(val title: String, val doneBytes: Long, val totalBytes: Long, val doneFiles: Int, val totalFiles: Int, val current: String, val finished: Boolean = false, val message: String = "")
class ConflictAsk(val target: File, val future: CompletableFuture<Pair<Conflict, Boolean>>)

/** A copy or move running in the background: progress in the app and in a notification, conflicts asked in the app. */
object Ops {
    private val _state = MutableStateFlow<OpState?>(null)
    val state: StateFlow<OpState?> = _state
    private val _conflict = MutableStateFlow<ConflictAsk?>(null)
    val conflict: StateFlow<ConflictAsk?> = _conflict
    /** Bumped whenever files changed, so open folders reload. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed
    fun bump() { _changed.value++ }
    @Volatile var cancel = false
    private var pending: Triple<Boolean, List<File>, File>? = null

    fun start(ctx: Context, move: Boolean, sources: List<File>, dest: File) {
        if (_state.value?.finished == false) return
        pending = Triple(move, sources, dest); cancel = false
        _state.value = OpState(if (move) "Moving" else "Copying", 0, 0, 0, 0, "")
        ContextCompat.startForegroundService(ctx, Intent(ctx, OpService::class.java))
    }
    fun dismiss() { _state.value = null }

    internal fun run(ctx: Context, notify: (OpState) -> Unit) {
        val (move, sources, dest) = pending ?: return
        pending = null
        val title = if (move) "Moving" else "Copying"
        val prog = OpProgress()
        var all: Conflict? = null
        val resolve: (File) -> Conflict = { t ->
            val a = all
            if (a != null) a else {
                val ask = ConflictAsk(t, CompletableFuture())
                _conflict.value = ask
                val (c, forAll) = try { ask.future.get() } catch (_: Exception) { Conflict.Skip to false }
                _conflict.value = null
                if (forAll) all = c
                c
            }
        }
        val ticker = Thread {
            try { while (true) { Thread.sleep(300); val s = OpState(title, prog.doneBytes, prog.totalBytes, prog.doneFiles, prog.totalFiles, prog.current); _state.value = s; notify(s) } } catch (_: InterruptedException) { }
        }
        ticker.start()
        val r = try {
            if (move) FileOps.move(sources, dest, resolve, prog, canceled = { cancel }) else FileOps.copy(sources, dest, resolve, prog, canceled = { cancel })
        } finally { ticker.interrupt() }
        val msg = buildString {
            append("${r.done.size} item(s) ${if (move) "moved" else "copied"}")
            if (r.skipped > 0) append(", ${r.skipped} skipped")
            if (r.errors.isNotEmpty()) append(". ${r.errors.size} problem(s): ${r.errors.first().second}")
        }
        // new files appear in the Gallery
        val scan = r.done.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
        if (scan.isNotEmpty()) android.media.MediaScannerConnection.scanFile(ctx, scan.take(500).map { it.path }.toTypedArray(), null, null)
        _state.value = OpState(title, prog.doneBytes, prog.totalBytes, prog.doneFiles, prog.totalFiles, "", true, msg)
        bump()
    }
}

class OpService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("TabExplorer").setContentText("Starting...").setOngoing(true).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(NOTE_OPS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTE_OPS, n)
        Thread {
            val nm = getSystemService(NotificationManager::class.java)
            Ops.run(this) { s ->
                val pct = if (s.totalBytes > 0) (s.doneBytes * 100 / s.totalBytes).toInt() else 0
                nm.notify(NOTE_OPS, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle("${s.title}: ${s.doneFiles} of ${s.totalFiles} files").setContentText(s.current).setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true).build())
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }
    companion object {
        const val CHANNEL = "tabexplorer"; const val NOTE_OPS = 11; const val NOTE_SHARE = 12; const val NOTE_ASK = 13; const val NOTE_GOT = 14; const val NOTE_SENT = 15
        fun createChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "TabExplorer", NotificationManager.IMPORTANCE_LOW))
            if (nm.getNotificationChannel("alerts") == null) nm.createNotificationChannel(NotificationChannel("alerts", "Files from your PCs", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
