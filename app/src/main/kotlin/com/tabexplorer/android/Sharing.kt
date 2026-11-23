package com.tabexplorer.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tabexplorer.core.humanSize
import com.tabexplorer.share.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

class SendSnap(val to: String, val phase: String, val doneBytes: Long, val totalBytes: Long, val doneFiles: Int, val totalFiles: Int, val current: String,
               val finished: Boolean = false, val message: String = "", val failed: Boolean = false, val job: SendJob? = null)

/** One send at a time to a PC: progress for the screen, and the result in plain words. */
object Sender {
    val state = MutableStateFlow<SendSnap?>(null)
    fun clear() { if (state.value?.finished != false) state.value = null }

    fun start(peer: Peer, items: List<SendItem>) {
        val app = App.instance
        val job = SendJob(items)
        fun snap(fin: Boolean = false, msg: String = "", failed: Boolean = false) = SendSnap(peer.name, job.phase, job.doneBytes, job.totalBytes, job.doneFiles, job.totalFiles, job.current, fin, msg, failed, job)
        job.onProgress = { state.value = snap() }
        state.value = snap()
        Thread {
            try {
                app.share.send(peer.id, job)
                val extra = if (job.errors.isNotEmpty()) " ${job.errors.size} problem(s): ${job.errors.first().second}" else ""
                state.value = snap(true, "Sent ${job.sentCount} file(s) to ${peer.name}.$extra")
            } catch (e: ShareException) { state.value = snap(true, e.message ?: "Couldn't send.", true) }
            catch (e: Exception) { state.value = snap(true, "Couldn't send: ${e.message}", true) }
        }.start()
    }
}

fun itemsOfFiles(files: List<File>): List<SendItem> {
    val out = ArrayList<SendItem>()
    fun walk(f: File, rel: String) {
        if (f.isDirectory) { out.add(SendItem(rel, true)); (f.listFiles() ?: emptyArray()).sortedBy { it.name }.forEach { walk(it, "$rel/${it.name}") } }
        else if (f.isFile) out.add(SendItem(rel, false, f.length(), f.lastModified()) { f.inputStream() })
    }
    files.forEach { walk(it, it.name) }
    return out
}

fun itemsOfUris(cr: ContentResolver, uris: List<Uri>): List<SendItem> = uris.mapIndexed { i, u ->
    var name = "shared-$i"; var size = 0L
    if (u.scheme == "file") { name = File(u.path!!).name; size = File(u.path!!).length() }
    else try { cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = if (c.isNull(1)) 0 else c.getLong(1) } } } catch (_: Exception) { }
    val clean = name.replace('/', '_')
    if (size <= 0) size = try { cr.openAssetFileDescriptor(u, "r")?.use { it.length.coerceAtLeast(0) } ?: 0 } catch (_: Exception) { 0 }
    SendItem(clean, false, size, System.currentTimeMillis()) { cr.openInputStream(u) ?: throw java.io.IOException("Couldn't open $clean") }
}

/** The list of PCs on the network, to pick where to send. */
@Composable
fun SendPanel(items: List<SendItem>, onClose: () -> Unit) {
    val app = App.instance
    val v by app.peersVersion.collectAsState()
    val snap by Sender.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { app.share.announce("who"); while (true) { delay(3000); refresh++ } }
    val peers = remember(v, refresh) { app.share.visiblePeers() }
    val s = snap
    Column(Modifier.fillMaxSize()) {
        if (s != null) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if (s.finished) (if (s.failed) "Not sent" else "Done") else "Sending to ${s.to}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                if (!s.finished) {
                    if (s.phase == "waiting") Text("Waiting for ${s.to} to say yes. Look at that PC for the question (the first time only).")
                    else { Text("${s.doneFiles} of ${s.totalFiles} files · ${humanSize(s.doneBytes)} of ${humanSize(s.totalBytes)}"); Text(s.current, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
                    LinearProgressIndicator(progress = { if (s.totalBytes > 0) s.doneBytes.toFloat() / s.totalBytes else 0f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    OutlinedButton(onClick = { s.job?.canceled = true; Sender.state.value = SendSnap(s.to, "", 0, 0, 0, 0, "", true, "Cancelled.", true) }) { Text("Cancel") }
                } else {
                    Text(s.message)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { Sender.clear(); onClose() }) { Text("OK") }
                }
            }
            return@Column
        }
        Text("${items.count { !it.dir }} file(s), ${humanSize(items.filter { !it.dir }.sumOf { it.size })}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        if (!app.share.running) Text("Sharing isn't running. ${app.share.error}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (peers.isEmpty()) Column(Modifier.padding(16.dp)) {
            Text("No PCs found yet.", style = MaterialTheme.typography.titleMedium)
            Text("Open TabExplorer on your PC (with Sharing on), on the same Wi-Fi as this phone. It can take up to 20 seconds to show up.", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn {
            items(peers, key = { it.id }) { p ->
                ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(if (p.online()) "On · code ${p.code}" else "Not seen lately · code ${p.code}") },
                    leadingContent = { Icon(Icons.Filled.Computer, null) },
                    modifier = Modifier.clickable(enabled = p.online() && items.isNotEmpty()) { Sender.start(p, items) })
            }
        }
    }
}

@Composable
fun SendToScreen(nav: Nav, files: List<File>) {
    val items = remember(files) { itemsOfFiles(files) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Send to PC", onBack = { if (Sender.state.value?.finished != false) Sender.clear(); nav.back() })
        SendPanel(items) { nav.back() }
    }
}

@Composable
fun SharingScreen(nav: Nav) {
    val app = App.instance
    val share = app.share
    val v by app.peersVersion.collectAsState()
    var name by remember { mutableStateOf(app.prefs.deviceName) }
    var ready by remember { mutableStateOf(app.prefs.ready) }
    var keepBoth by remember { mutableStateOf(app.prefs.keepBoth) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { share.announce("who"); while (true) { delay(2000); tick++ } }
    val peers = remember(v, tick) { share.visiblePeers() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Sharing", "With TabExplorer on your PCs", onBack = { nav.back() })
        LazyColumn {
            item {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(name, { name = it.take(40); app.prefs.deviceName = name.trim(); share.announce("here") }, label = { Text("This phone's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Code ${share.code}. Your PC shows the same code beside this name, so you can tell it's this phone.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    if (share.error.isNotEmpty()) Text("Sharing couldn't start: ${share.error}", color = MaterialTheme.colorScheme.error)
                }
            }
            item {
                ListItem(headlineContent = { Text("Ready to receive") },
                    supportingContent = { Text(if (ready) "On. A small notification shows while it's on, because Android stops background apps." else "Off. Files can only be received while TabExplorer is open.") },
                    trailingContent = { Switch(ready, { ready = it; app.prefs.ready = it; app.refreshSharing() }) })
                ListItem(headlineContent = { Text("If a name is already taken") },
                    supportingContent = { Text(if (keepBoth) "Keep both files" else "Replace the old file") },
                    trailingContent = { Switch(keepBoth, { keepBoth = it; app.prefs.keepBoth = it }) })
                ListItem(headlineContent = { Text("Received files go to") }, supportingContent = { Text(Storage.receiveFolder.path) },
                    modifier = Modifier.clickable { Storage.receiveFolder.mkdirs(); nav.go(Screen.Browse(Storage.receiveFolder)) })
            }
            item { SectionTitle("Your PCs") }
            if (peers.isEmpty()) item { Text("No PCs found. Open TabExplorer on your PC with Sharing on, on the same Wi-Fi. Wi-Fi on phones sometimes hides devices from each other; if that happens, try again in a minute.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            items(peers, key = { it.id }) { p ->
                val trusted = share.identity.isTrusted(p.id, p.pub)
                ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text((if (p.online()) "On" else "Not seen lately") + " · code ${p.code}" + (if (p.address.isNotEmpty()) " · ${p.address}" else "")) },
                    leadingContent = { Icon(Icons.Filled.Computer, null) },
                    trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (trusted) "May send me files" else "Asks first", style = MaterialTheme.typography.labelSmall)
                        Switch(trusted, { share.setTrusted(p.id, it); tick++ })
                        IconButton(onClick = { share.forget(p.id) }) { Icon(Icons.Filled.Close, "Forget") }
                    } })
            }
        }
    }
}

/** Share > TabExplorer from other apps: pick a PC and send. */
class ShareTargetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = ArrayList<Uri>()
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val items = if (uris.isNotEmpty()) itemsOfUris(contentResolver, uris)
            else if (text != null) listOf(SendItem("Shared text.txt", false, text.toByteArray().size.toLong(), System.currentTimeMillis()) { text.toByteArray().inputStream() })
            else emptyList()
        setContent { TabExplorerTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.statusBarsPadding()) {
            TopBar("Send to PC", onBack = { Sender.clear(); finish() })
            SendPanel(items) { finish() }
        } } } }
    }
}
