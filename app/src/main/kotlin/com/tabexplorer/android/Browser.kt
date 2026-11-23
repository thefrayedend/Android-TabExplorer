package com.tabexplorer.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tabexplorer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

object Clipboard { var files by mutableStateOf<List<File>>(emptyList()); var move by mutableStateOf(false) }

@Composable
fun FileThumb(f: File, size: Int) {
    val k = Kinds.of(f)
    val mod = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp))
    if (k == Kind.Image || k == Kind.Video) AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = mod)
    else Box(mod.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) { Icon(iconFor(k), null, Modifier.size((size / 2).dp)) }
}

fun iconFor(k: Kind) = when (k) {
    Kind.Folder -> Icons.Filled.Folder; Kind.Image -> Icons.Filled.Image; Kind.Video -> Icons.Filled.Movie; Kind.Audio -> Icons.Filled.MusicNote
    Kind.Document -> Icons.Filled.Description; Kind.Text -> Icons.Filled.TextSnippet; Kind.Pdf -> Icons.Filled.PictureAsPdf; Kind.Apk -> Icons.Filled.Android
    Kind.Archive -> Icons.Filled.FolderZip; Kind.Other -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(nav: Nav, dir: File, snack: SnackbarHostState) {
    val ctx = LocalContext.current
    val app = App.instance
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf(app.prefs.sort) }
    var grid by remember { mutableStateOf(app.prefs.grid) }
    var hidden by remember { mutableStateOf(app.prefs.showHidden) }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<File>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Dlg?>(null) }
    val changed by Ops.changed.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val op by Ops.state.collectAsState()
    val selecting = selected.isNotEmpty()

    LaunchedEffect(dir) { app.prefs.visited(dir) }
    LaunchedEffect(dir, sort, hidden, changed, reload) {
        withContext(Dispatchers.IO) {
            val l = dir.listFiles()
            if (l == null) { error = if (dir.exists()) "Android keeps this folder private, so no file manager can open it." else "This folder isn't there any more." ; entries = emptyList() }
            else { error = null; entries = Sorting.sort(l.filter { hidden || !it.name.startsWith(".") }, sort) }
        }
        selected = selected.filter { it.exists() }.toSet()
    }
    BackHandlerSelect(selecting) { selected = emptySet() }

    fun act(f: File) {
        if (selecting) { selected = if (f in selected) selected - f else selected + f; return }
        if (f.isDirectory) nav.go(Screen.Browse(f)) else openFile(nav, f, entries.orEmpty().filter { it.isFile })
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            TopAppBar(title = { Text("${selected.size} selected") }, navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Clear") } },
                actions = { TextButton(onClick = { selected = entries.orEmpty().toSet() }) { Text("All") } })
        } else {
            TopBar(dir.name.ifEmpty { "Storage" }, Storage.breadcrumb(dir), onBack = { nav.back() }) {
                IconButton(onClick = { grid = !grid; app.prefs.grid = grid }) { Icon(if (grid) Icons.Filled.ViewList else Icons.Filled.GridView, "View") }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Filled.SwapVert, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        for (b in SortBy.values()) DropdownMenuItem(text = { Text(b.name + if (sort.by == b) (if (sort.descending) "  ↓" else "  ↑") else "") }, onClick = {
                            sort = if (sort.by == b) sort.copy(descending = !sort.descending) else Sort(b); app.prefs.sort = sort; sortMenu = false })
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("New folder") }, onClick = { menu = false; dialog = Dlg.NewFolder })
                        DropdownMenuItem(text = { Text("New text file") }, onClick = { menu = false; dialog = Dlg.NewFile })
                        DropdownMenuItem(text = { Text(if (hidden) "Hide hidden files" else "Show hidden files") }, onClick = { menu = false; hidden = !hidden; app.prefs.showHidden = hidden })
                        DropdownMenuItem(text = { Text(if (app.prefs.isPinned(dir)) "Unpin this folder" else "Pin this folder") }, onClick = { menu = false; app.prefs.togglePin(dir); reload++ })
                        DropdownMenuItem(text = { Text("Properties") }, onClick = { menu = false; dialog = Dlg.Props(listOf(dir)) })
                    }
                }
            }
        }

        val l = entries
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(error!!) }
                l.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("This folder is empty.") }
                grid -> LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(8.dp)) {
                    items(l, key = { it.path }) { f ->
                        val sel = f in selected
                        Column(Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .combinedClickable(onClick = { act(f) }, onLongClick = { selected = selected + f }).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (f.isDirectory) Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Folder, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary) }
                            else FileThumb(f, 92)
                            Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                else -> LazyColumn {
                    items(l, key = { it.path }) { f ->
                        val sel = f in selected
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(df.format(Date(f.lastModified())) + if (f.isFile) " · " + humanSize(f.length()) else "") },
                            leadingContent = {
                                if (sel) Icon(Icons.Filled.CheckCircle, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                else if (f.isDirectory) Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) else FileThumb(f, 40)
                            },
                            modifier = Modifier.combinedClickable(onClick = { act(f) }, onLongClick = { selected = selected + f }))
                    }
                }
            }
        }

        // what is running now
        op?.let { s ->
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    if (s.finished) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(s.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { Ops.dismiss() }) { Text("OK") } }
                    } else {
                        Text("${s.title}: ${s.doneFiles} of ${s.totalFiles} files", style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(progress = { if (s.totalBytes > 0) s.doneBytes.toFloat() / s.totalBytes else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(s.current, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall); TextButton(onClick = { Ops.cancel = true }) { Text("Cancel") } }
                    }
                }
            }
        }

        if (selecting) {
            val sel = selected.toList()
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BarButton(Icons.Filled.ContentCopy, "Copy") { Clipboard.files = sel; Clipboard.move = false; selected = emptySet() }
                    BarButton(Icons.Filled.ContentCut, "Move") { Clipboard.files = sel; Clipboard.move = true; selected = emptySet() }
                    BarButton(Icons.Filled.Delete, "Delete") {
                        scope.launch {
                            val trashed = withContext(Dispatchers.IO) { sel.mapNotNull { runCatching { app.bin.trash(it) }.getOrNull() } }
                            selected = emptySet(); Ops.bump()
                            val r = snack.showSnackbar("${trashed.size} moved to the Recycle Bin", "Undo", duration = SnackbarDuration.Long)
                            if (r == SnackbarResult.ActionPerformed) { withContext(Dispatchers.IO) { trashed.forEach { runCatching { app.bin.restore(it) } } }; Ops.bump() }
                        }
                    }
                    BarButton(Icons.Filled.Share, "Share") { shareFiles(ctx, sel) }
                    BarButton(Icons.Filled.Computer, "Send to PC") { nav.go(Screen.SendTo(sel)) }
                    var more by remember { mutableStateOf(false) }
                    Box {
                        BarButton(Icons.Filled.MoreVert, "More") { more = true }
                        DropdownMenu(more, { more = false }) {
                            if (sel.size == 1) DropdownMenuItem(text = { Text("Rename") }, onClick = { more = false; dialog = Dlg.Rename(sel[0]) })
                            DropdownMenuItem(text = { Text("Properties") }, onClick = { more = false; dialog = Dlg.Props(sel) })
                            if (sel.size == 1 && sel[0].isDirectory) DropdownMenuItem(text = { Text("Pin folder") }, onClick = { more = false; app.prefs.togglePin(sel[0]); selected = emptySet() })
                        }
                    }
                }
            }
        } else if (Clipboard.files.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Clipboard.files.size} to ${if (Clipboard.move) "move" else "copy"}", Modifier.weight(1f))
                    TextButton(onClick = { Clipboard.files = emptyList() }) { Text("Cancel") }
                    Button(onClick = { Ops.start(ctx, Clipboard.move, Clipboard.files, dir); Clipboard.files = emptyList() }) { Text("Paste here") }
                }
            }
        }
    }

    when (val d = dialog) {
        Dlg.NewFolder -> NameDialog("New folder", "Folder name", "", "Create", { dialog = null }) { n -> runCatching { FileOps.newFolder(dir, n) }.onFailure { app.say(it.message ?: "Couldn't") }; dialog = null; Ops.bump() }
        Dlg.NewFile -> NameDialog("New text file", "File name", "New file.txt", "Create", { dialog = null }) { n -> runCatching { FileOps.newFile(dir, n) }.onFailure { app.say(it.message ?: "Couldn't") }; dialog = null; Ops.bump() }
        is Dlg.Rename -> NameDialog("Rename", "Name", d.f.name, "Rename", { dialog = null }) { n -> runCatching { FileOps.rename(d.f, n) }.onFailure { app.say(it.message ?: "Couldn't") }; dialog = null; selected = emptySet(); Ops.bump() }
        is Dlg.Props -> PropertiesDialog(d.files) { dialog = null }
        null -> {}
    }
    ConflictDialog()
}

sealed interface Dlg {
    data object NewFolder : Dlg
    data object NewFile : Dlg
    data class Rename(val f: File) : Dlg
    data class Props(val files: List<File>) : Dlg
}

@Composable fun BackHandlerSelect(on: Boolean, f: () -> Unit) = androidx.activity.compose.BackHandler(enabled = on, onBack = f)

@Composable
fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
fun NameDialog(title: String, label: String, initial: String, ok: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onOk(text.trim()) }) { Text(ok) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun PropertiesDialog(files: List<File>, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf<Long?>(null) }
    var count by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(files) { withContext(Dispatchers.IO) { size = files.sumOf { FileOps.sizeOf(it) }; count = files.sumOf { FileOps.countFiles(it) } } }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }, title = { Text(if (files.size == 1) files[0].name.ifEmpty { "Storage" } else "${files.size} items") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (files.size == 1) {
                val f = files[0]
                Text("Type: ${Storage.kindLabel(Kinds.of(f))}"); Text("Where: ${f.parent ?: "/"}")
                Text("Modified: ${df.format(Date(f.lastModified()))}")
                if (f.isDirectory) Text("Contains: ${count?.let { "$it file(s)" } ?: "counting..."}")
            }
            Text("Size: ${size?.let { humanSize(it) + " ($it bytes)" } ?: "counting..."}")
        }
    })
}

@Composable
fun ConflictDialog() {
    val ask by Ops.conflict.collectAsState()
    val a = ask ?: return
    var all by remember(a) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = {}, title = { Text("\"${a.target.name}\" is already there") },
        text = { Column { Text("What should happen?"); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(all, { all = it }); Text("Do this for all") } } },
        confirmButton = { Row { TextButton(onClick = { a.future.complete(Conflict.Replace to all) }) { Text("Replace") }; TextButton(onClick = { a.future.complete(Conflict.KeepBoth to all) }) { Text("Keep both") } } },
        dismissButton = { TextButton(onClick = { a.future.complete(Conflict.Skip to all) }) { Text("Skip") } })
}
