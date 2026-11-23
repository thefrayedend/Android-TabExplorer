package com.tabexplorer.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabexplorer.core.Kind
import com.tabexplorer.core.humanSize
import java.io.File

@Composable
fun SectionTitle(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))

@Composable
fun Tile(icon: ImageVector, label: String, sub: String? = null, onClick: () -> Unit) {
    Column(Modifier.width(92.dp).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null) } }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val app = App.instance
    val volumes = remember { Storage.volumes(ctx) }
    val quick = remember { Storage.quickPlaces() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { tick++ }
    val recents = remember(tick) { app.prefs.recentFiles.take(8) }
    val places = remember(tick) { app.prefs.recentPlaces.take(8) }
    val pins = remember(tick) { app.prefs.pins }
    val peersV by app.peersVersion.collectAsState()
    val pcs = remember(peersV) { app.share.visiblePeers() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("TabExplorer", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.go(Screen.Bin) }) { Icon(Icons.Filled.Delete, "Recycle Bin") }
                IconButton(onClick = { nav.go(Screen.Sharing) }) { Icon(Icons.Filled.Share, "Sharing") }
            }
        }
        item { SectionTitle("Storage") }
        items(volumes) { v ->
            val frac = if (v.total > 0) 1f - v.free.toFloat() / v.total else 0f
            Column(Modifier.fillMaxWidth().clickable { nav.go(Screen.Browse(v.root)) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (v.removable) Icons.Filled.SdCard else Icons.Filled.PhoneAndroid, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${humanSize(v.free)} free of ${humanSize(v.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        item { SectionTitle("Quick places") }
        item { LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) { items(quick) { q -> Tile(when (q.label) { "Camera" -> Icons.Filled.PhotoCamera; "Downloads" -> Icons.Filled.Download; "Screenshots" -> Icons.Filled.Screenshot; "Music" -> Icons.Filled.MusicNote; "Movies" -> Icons.Filled.Movie; else -> Icons.Filled.Description }, q.label) { nav.go(Screen.Browse(q.dir)) } } } }
        if (pins.isNotEmpty()) {
            item { SectionTitle("Pinned folders") }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) { items(pins) { f -> Tile(Icons.Filled.PushPin, f.name) { nav.go(Screen.Browse(f)) } } } }
        }
        item { SectionTitle("Media by type") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(listOf(Triple(Kind.Image, "Photos", Icons.Filled.Image), Triple(Kind.Video, "Videos", Icons.Filled.Videocam), Triple(Kind.Audio, "Music", Icons.Filled.LibraryMusic),
                    Triple(Kind.Document, "Documents", Icons.Filled.Description), Triple(Kind.Apk, "Apps (APK)", Icons.Filled.Android), Triple(Kind.Archive, "Archives", Icons.Filled.FolderZip))) { (k, l, i) ->
                    Tile(i, l) { nav.go(Screen.Category(k, l)) }
                }
            }
        }
        if (pcs.isNotEmpty()) {
            item { SectionTitle("Your PCs") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(pcs) { p -> Tile(Icons.Filled.Computer, p.name, if (p.online()) "on" else "not seen") { nav.go(Screen.Sharing) } }
                }
            }
        }
        if (places.isNotEmpty()) {
            item { SectionTitle("Recent places") }
            items(places) { f -> ListItem(headlineContent = { Text(f.name.ifEmpty { "/" }) }, supportingContent = { Text(Storage.breadcrumb(f), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.Filled.Folder, null) }, modifier = Modifier.clickable { nav.go(Screen.Browse(f)) }) }
        }
        if (recents.isNotEmpty()) {
            item { SectionTitle("Recent files") }
            items(recents) { f -> ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(humanSize(f.length())) },
                leadingContent = { FileThumb(f, 40) }, modifier = Modifier.clickable { openFile(nav, f, listOf(f)) }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun CategoryScreen(nav: Nav, s: Screen.Category) {
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(s) { files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MediaIndex.query(ctx, s.kind) } }
    Column(Modifier.fillMaxSize()) {
        TopBar(s.title, onBack = { nav.back() })
        val l = files
        if (l == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (l.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet.") }
        else LazyColumn { items(l, key = { it.path }) { f ->
            ListItem(headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text("${humanSize(f.length())} · ${f.parent?.let { Storage.breadcrumb(File(it)) }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { FileThumb(f, 44) }, modifier = Modifier.clickable { openFile(nav, f, l) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = { Column { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis); if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, actions = actions)
}
