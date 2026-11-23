package com.tabexplorer.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tabexplorer.core.Kind
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Browse(val dir: File) : Screen
    data class Category(val kind: Kind, val title: String) : Screen
    data class Photos(val files: List<File>, val index: Int) : Screen
    data class Video(val file: File) : Screen
    data object NowPlaying : Screen
    data class Text(val file: File) : Screen
    data class Pdf(val file: File) : Screen
    data object Bin : Screen
    data object Sharing : Screen
    data class SendTo(val files: List<File>) : Screen
}

class Nav(initial: Screen) {
    var stack by mutableStateOf(listOf(initial))
    val top: Screen get() = stack.last()
    fun go(s: Screen) { stack = stack + s }
    fun back(): Boolean { if (stack.size <= 1) return false; stack = stack.dropLast(1); return true }
    fun replace(s: Screen) { stack = stack.dropLast(1) + s }
    fun home() { stack = listOf(Screen.Home) }
}

class MainActivity : ComponentActivity() {
    private var pendingOpen by mutableStateOf<File?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOpen = intent?.data?.takeIf { it.scheme == "file" }?.path?.let { File(it) }
        setContent {
            TabExplorerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate { Root(pendingOpen) { pendingOpen = null } }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); intent.data?.takeIf { it.scheme == "file" }?.path?.let { pendingOpen = File(it) } }
}

fun hasAllFiles(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(hasAllFiles()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) ok = hasAllFiles() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(ok) {
        if (ok) {
            val want = ArrayList<String>()
            if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 33) { want.add(Manifest.permission.READ_MEDIA_IMAGES); want.add(Manifest.permission.READ_MEDIA_VIDEO); want.add(Manifest.permission.READ_MEDIA_AUDIO) }
            else if (Build.VERSION.SDK_INT < 30) { want.add(Manifest.permission.READ_EXTERNAL_STORAGE); want.add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            if (want.isNotEmpty()) notif.launch(want.toTypedArray())
            (ctx.applicationContext as App).refreshSharing()
        }
    }
    if (ok) { content(); return }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("TabExplorer", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text("To show all your files, Android needs you to allow \"All files access\" for TabExplorer. It's how every file manager works.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Text("Even with it, other apps' private folders (Android/data and Android/obb) stay locked. That is Android's rule for every file manager.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            val i = try { Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")) } catch (_: Exception) { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
            try { ctx.startActivity(i) } catch (_: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }) { Text("Allow all files access") }
    }
}

@Composable
fun Root(open: File?, opened: () -> Unit) {
    val app = App.instance
    val nav = remember { Nav(Screen.Home) }
    val snack = remember { SnackbarHostState() }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    LaunchedEffect(open) { if (open != null) { openFile(nav, open, listOf(open)); opened() } }
    val toast by app.toast.collectAsState()
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); app.toastShown() } }
    val incoming by app.incoming.collectAsState()
    incoming?.let { inc ->
        AlertDialog(onDismissRequest = { app.answer(inc, false) },
            title = { Text("${inc.req.name} wants to send you files") },
            text = { Text("${inc.req.count} file(s), ${com.tabexplorer.core.humanSize(inc.req.bytes)}.\n\nCheck that this code is the one shown on that device: ${inc.req.code}\n\nIf you accept, ${inc.req.name} can send you files without asking again.") },
            confirmButton = { TextButton(onClick = { app.answer(inc, true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { app.answer(inc, false) }) { Text("Decline") } })
    }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, bottomBar = { MiniPlayer(nav) }, contentWindowInsets = WindowInsets(0)) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().statusBarsPadding()) {
            when (val s = nav.top) {
                Screen.Home -> HomeScreen(nav)
                is Screen.Browse -> BrowserScreen(nav, s.dir, snack)
                is Screen.Category -> CategoryScreen(nav, s)
                is Screen.Photos -> PhotoViewer(nav, s.files, s.index)
                is Screen.Video -> VideoScreen(nav, s.file)
                Screen.NowPlaying -> NowPlayingScreen(nav)
                is Screen.Text -> TextScreen(nav, s.file, snack)
                is Screen.Pdf -> PdfScreen(nav, s.file)
                Screen.Bin -> BinScreen(nav, snack)
                Screen.Sharing -> SharingScreen(nav)
                is Screen.SendTo -> SendToScreen(nav, s.files)
            }
        }
    }
}
