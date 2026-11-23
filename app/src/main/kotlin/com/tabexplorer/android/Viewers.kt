package com.tabexplorer.android

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.tabexplorer.core.Conflict
import com.tabexplorer.core.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// ---------------------------------------------------------------- photos
@Composable
fun PhotoViewer(nav: Nav, files: List<File>, start: Int) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, files.size - 1)) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    var bars by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { files[it].path }) { i ->
            ZoomImage(files[i], onZoom = { zoomed = it }, onTap = { bars = !bars })
        }
        if (bars) {
            val f = files[pager.currentPage]
            Row(Modifier.fillMaxWidth().background(Color(0x99000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) { Text(f.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${pager.currentPage + 1} of ${files.size} · ${humanSize(f.length())}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = { shareFiles(ctx, listOf(f)) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                IconButton(onClick = { nav.go(Screen.SendTo(listOf(f))) }) { Icon(Icons.Filled.Computer, "Send to PC", tint = Color.White) }
            }
        }
    }
}

@Composable
fun ZoomImage(f: File, onZoom: (Boolean) -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2; val my = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f; onZoom(scale > 1f) }) }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    val zoom = ev.calculateZoom(); val pan = ev.calculatePan()
                    if (ev.changes.size > 1 || scale > 1f) {
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale <= 1f) Offset.Zero else clamp(offset + pan, scale)
                        onZoom(scale > 1f)
                        ev.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }, contentAlignment = Alignment.Center) {
        AsyncImage(model = f, contentDescription = f.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}

// ---------------------------------------------------------------- video
@Composable
fun VideoScreen(nav: Nav, file: File) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val player = remember {
        ExoPlayer.Builder(ctx).build().also { p ->
            val subs = listOf("srt" to MimeTypes.APPLICATION_SUBRIP, "vtt" to MimeTypes.TEXT_VTT).mapNotNull { (ext, mime) ->
                File(file.parentFile, file.nameWithoutExtension + "." + ext).takeIf { it.exists() }?.let { MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(it)).setMimeType(mime).setLanguage("und").setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT).build() }
            }
            p.setMediaItem(MediaItem.Builder().setUri(Uri.fromFile(file)).setSubtitleConfigurations(subs).build()); p.prepare(); p.playWhenReady = true
        }
    }
    var hud by remember { mutableStateOf("") }
    var view by remember { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(Unit) {
        Player.controller?.pause()
        onDispose { player.release(); activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    LaunchedEffect(hud) { if (hud.isNotEmpty()) { kotlinx.coroutines.delay(900); hud = "" } }
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> PlayerView(c).also { it.player = player; it.keepScreenOn = true; it.layoutParams = ViewGroup.LayoutParams(-1, -1); view = it } }, modifier = Modifier.fillMaxSize())
        // touch area for gestures: the middle of the screen, so the player's own buttons stay usable
        Box(Modifier.fillMaxSize().padding(top = 56.dp, bottom = 110.dp).pointerInput(Unit) {
            detectTapGestures(onTap = { view?.let { v -> if (v.isControllerFullyVisible) v.hideController() else v.showController() } },
                onDoubleTap = { if (player.isPlaying) player.pause() else player.play() })
        }.pointerInput(Unit) {
            var mode = 0 // 1 seek, 2 brightness, 3 volume
            var startPos = 0L; var total = 0f; var startVol = 0; var startBright = 0.5f; var left = false
            detectDragGestures(
                onDragStart = { o -> mode = 0; total = 0f; startPos = player.currentPosition; left = o.x < size.width / 2
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    startBright = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f },
                onDrag = { ch, d ->
                    ch.consume()
                    total += 0f
                    if (mode == 0) mode = if (abs(d.x) > abs(d.y)) 1 else if (left) 2 else 3
                    when (mode) {
                        1 -> { dragX += d.x; val target = (startPos + (dragX * 60).toLong()).coerceIn(0, player.duration.coerceAtLeast(0)); player.seekTo(target); hud = "${clock(target)} / ${clock(player.duration.coerceAtLeast(0))}" }
                        2 -> { dragY += d.y; val b = (startBright - dragY / size.height).coerceIn(0.02f, 1f); activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = b } }; hud = "Brightness ${(b * 100).toInt()}%" }
                        3 -> { dragY += d.y; val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val v = (startVol - dragY / size.height * max * 1.5f).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); hud = "Volume ${v * 100 / max}%" }
                    }
                },
                onDragEnd = { dragX = 0f; dragY = 0f }, onDragCancel = { dragX = 0f; dragY = 0f })
        })
        Row(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(file.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (hud.isNotEmpty()) Box(Modifier.align(Alignment.Center).background(Color(0xAA000000), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(16.dp)) { Text(hud, color = Color.White, style = MaterialTheme.typography.titleMedium) }
    }
}
private var dragX = 0f
private var dragY = 0f

// ---------------------------------------------------------------- text
@Composable
fun TextScreen(nav: Nav, file: File, snack: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf<String?>(null) }
    var original by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            if (file.length() > 2_000_000) problem = "This file is too big to edit here (${humanSize(file.length())})."
            else runCatching { file.readText() }.onSuccess { text = it; original = it }.onFailure { problem = it.message }
        }
    }
    val dirty = text != null && text != original
    androidx.activity.compose.BackHandler(enabled = dirty) { scope.launch { if (snack.showSnackbar("Unsaved changes", "Discard") == SnackbarResult.ActionPerformed) nav.back() } }
    Column(Modifier.fillMaxSize()) {
        TopBar(file.name + if (dirty) " *" else "", onBack = { nav.back() }) {
            if (dirty) IconButton(onClick = { scope.launch(Dispatchers.IO) { runCatching { file.writeText(text!!) }.onSuccess { original = text!!; App.instance.say("Saved") }.onFailure { App.instance.say("Couldn't save: ${it.message}") } } }) { Icon(Icons.Filled.Save, "Save") }
        }
        if (problem != null) Text(problem!!, Modifier.padding(16.dp))
        else if (text == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else BasicTextField(text!!, { text = it }, textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp))
    }
}

// ---------------------------------------------------------------- pdf
@Composable
fun PdfScreen(nav: Nav, file: File) {
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val mutex = remember { Mutex() }
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            try { val d = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY); fd = d; renderer = PdfRenderer(d) }
            catch (e: Exception) { problem = "Couldn't open this PDF: ${e.message}" }
        }
    }
    DisposableEffect(Unit) { onDispose { runCatching { renderer?.close(); fd?.close() } } }
    Column(Modifier.fillMaxSize()) {
        TopBar(file.name, onBack = { nav.back() })
        val r = renderer
        if (problem != null) Text(problem!!, Modifier.padding(16.dp))
        else if (r == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            val w = LocalContext.current.resources.displayMetrics.widthPixels
            LazyColumn(Modifier.fillMaxSize().background(Color.DarkGray), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(r.pageCount) { i ->
                    var bmp by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) {
                        bmp = withContext(Dispatchers.IO) {
                            mutex.withLock {
                                runCatching {
                                    r.openPage(i).use { page ->
                                        val h = (w.toFloat() * page.height / page.width).toInt()
                                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    else Box(Modifier.fillMaxWidth().height(400.dp).background(Color.White), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Recycle Bin
@Composable
fun BinScreen(nav: Nav, snack: SnackbarHostState) {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(listOf<com.tabexplorer.core.TrashItem>()) }
    val changed by Ops.changed.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(changed, tick) { items = withContext(Dispatchers.IO) { app.bin.list() } }
    var confirmEmpty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Recycle Bin", "Emptied after 30 days", onBack = { nav.back() }) { if (items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") } }
        if (items.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here. Deleted files wait here for 30 days.") }
        else LazyColumn {
            items(items, key = { it.id }) { t ->
                ListItem(headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${humanSize(t.size)} · from ${File(t.originalPath).parent}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (t.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile, null) },
                    trailingContent = { Row {
                        IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { app.bin.restore(t, Conflict.KeepBoth) }.onFailure { app.say(it.message ?: "Couldn't restore") } }; Ops.bump(); tick++ } }) { Icon(Icons.Filled.Restore, "Restore") }
                        IconButton(onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { app.bin.purge(t) } }; tick++ } }) { Icon(Icons.Filled.DeleteForever, "Delete for good") }
                    } })
            }
        }
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false }, title = { Text("Empty the Recycle Bin?") }, text = { Text("${items.size} item(s) will be deleted for good.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; scope.launch { withContext(Dispatchers.IO) { app.bin.empty() }; tick++ } }) { Text("Delete for good") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } })
}
