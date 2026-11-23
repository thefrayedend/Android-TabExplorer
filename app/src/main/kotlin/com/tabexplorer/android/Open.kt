package com.tabexplorer.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.tabexplorer.core.Kind
import com.tabexplorer.core.Kinds
import com.tabexplorer.core.Sorting
import java.io.File

fun uriOf(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Sorting.extension(f)) ?: "*/*"

/** Opens a file in our own viewer when we have one, otherwise in another app. [siblings]: the files around it (the photos to swipe between, the songs to play on). */
fun openFile(nav: Nav, f: File, siblings: List<File>) {
    val app = App.instance
    app.prefs.opened(f)
    when (Kinds.of(f)) {
        Kind.Image -> { val imgs = siblings.filter { Kinds.of(it) == Kind.Image }.ifEmpty { listOf(f) }; nav.go(Screen.Photos(imgs, imgs.indexOf(f).coerceAtLeast(0))) }
        Kind.Video -> nav.go(Screen.Video(f))
        Kind.Audio -> { Player.playFolder(app, siblings.filter { Kinds.of(it) == Kind.Audio }.ifEmpty { listOf(f) }, f); nav.go(Screen.NowPlaying) }
        Kind.Pdf -> nav.go(Screen.Pdf(f))
        Kind.Text -> nav.go(Screen.Text(f))
        Kind.Apk -> openExternal(app, f, "application/vnd.android.package-archive")
        else -> openExternal(app, f, mimeOf(f))
    }
}

fun openExternal(ctx: Context, f: File, mime: String = mimeOf(f)) {
    try {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriOf(ctx, f), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(i, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { App.instance.say("No app on this phone can open ${f.name}.") }
}

fun shareFiles(ctx: Context, files: List<File>) {
    val fs = files.filter { it.isFile }
    if (fs.isEmpty()) { App.instance.say("Folders can't be shared this way. Open it and select files, or use Send to PC."); return }
    val uris = ArrayList(fs.map { uriOf(ctx, it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(mimeOf(fs[0]))
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
