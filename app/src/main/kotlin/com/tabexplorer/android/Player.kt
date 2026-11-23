package com.tabexplorer.android

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player as P
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tabexplorer.core.humanSize
import kotlinx.coroutines.delay
import java.io.File

/** The music player: a MediaController talking to [MusicService], so playback goes on with the app closed. */
object Player {
    var controller by mutableStateOf<MediaController?>(null)
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var count by mutableIntStateOf(0)
    var index by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(P.REPEAT_MODE_OFF)
    private var future: ListenableFuture<MediaController>? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()

    private val listener = object : P.Listener {
        override fun onEvents(player: P, events: P.Events) { sync(player) }
    }
    private fun sync(p: P) {
        title = p.mediaMetadata.title?.toString() ?: ""; playing = p.isPlaying; count = p.mediaItemCount; index = p.currentMediaItemIndex
        shuffle = p.shuffleModeEnabled; repeat = p.repeatMode
    }

    fun connect(ctx: Context, then: ((MediaController) -> Unit)? = null) {
        controller?.let { then?.invoke(it); return }
        if (then != null) waiting.add(then)
        if (future != null) return
        val token = SessionToken(ctx, ComponentName(ctx, MusicService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync(); future = f
        f.addListener({
            val c = f.get(); controller = c; c.addListener(listener); sync(c)
            waiting.forEach { it(c) }; waiting.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Plays [start] and then the rest of the folder, as TabExplorer does. */
    fun playFolder(ctx: Context, files: List<File>, start: File) {
        val items = files.map { f ->
            MediaItem.Builder().setUri(Uri.fromFile(f)).setMediaId(f.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(f.nameWithoutExtension).setArtist(f.parentFile?.name).build()).build()
        }
        val idx = files.indexOf(start).coerceAtLeast(0)
        // the service must be running as a started service before the controller binds, or it dies with the screen
        ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, MusicService::class.java)).let { }
        connect(ctx) { c -> c.setMediaItems(items, idx, 0L); c.prepare(); c.play() }
    }
}

@Composable
fun NowPlayingScreen(nav: Nav) {
    val c = Player.controller
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(c) { while (true) { if (c != null && !dragging) { pos = c.currentPosition; dur = c.duration.coerceAtLeast(0) }; delay(400) } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Now playing", onBack = { nav.back() })
        if (c == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.MusicNote, null, Modifier.size(120.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(Player.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${Player.index + 1} of ${Player.count}", style = MaterialTheme.typography.labelMedium)
            Slider(value = if (dragging) dragPos else if (dur > 0) pos.toFloat() / dur else 0f, onValueChange = { dragging = true; dragPos = it },
                onValueChangeFinished = { c.seekTo((dragPos * dur).toLong()); dragging = false })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(pos)); Text(clock(dur)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { c.shuffleModeEnabled = !c.shuffleModeEnabled }) { Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (Player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { c.seekToPrevious() }) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                FilledIconButton(onClick = { if (c.isPlaying) c.pause() else c.play() }, modifier = Modifier.size(64.dp)) { Icon(if (Player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(36.dp)) }
                IconButton(onClick = { c.seekToNext() }) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                // off -> repeat the folder -> repeat one song
                IconButton(onClick = { c.repeatMode = when (c.repeatMode) { P.REPEAT_MODE_OFF -> P.REPEAT_MODE_ALL; P.REPEAT_MODE_ALL -> P.REPEAT_MODE_ONE; else -> P.REPEAT_MODE_OFF } }) {
                    Icon(if (Player.repeat == P.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", tint = if (Player.repeat != P.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(when (Player.repeat) { P.REPEAT_MODE_ALL -> "Repeating the folder"; P.REPEAT_MODE_ONE -> "Repeating this song"; else -> "Plays the rest of the folder, then stops" }, style = MaterialTheme.typography.labelSmall)
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(List(Player.count) { it }) { i, _ ->
                val item = c.getMediaItemAt(i)
                ListItem(headlineContent = { Text(item.mediaMetadata.title?.toString() ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { if (i == Player.index) Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary) else Text("${i + 1}") },
                    modifier = Modifier.clickable { c.seekTo(i, 0L); c.play() })
            }
        }
    }
}

fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

/** A thin bar to get back to the music while browsing. */
@Composable
fun MiniPlayer(nav: Nav) {
    if (Player.count == 0 || nav.top == Screen.NowPlaying) return
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth().clickable { nav.go(Screen.NowPlaying) }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null); Spacer(Modifier.width(10.dp))
            Text(Player.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { Player.controller?.let { if (it.isPlaying) it.pause() else it.play() } }) { Icon(if (Player.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause") }
        }
    }
}
