package com.drivevault.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.drivevault.app.*
import com.drivevault.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(act: ComponentActivity, v: Vault, list: List<Item>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var full by remember { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var zoomCmd by remember { mutableStateOf(0 to 0) }   // (counter, direction) to drive +/- buttons
    val ctx = LocalContext.current
    val view = LocalView.current

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dview = LocalView.current
        val win = (dview.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
        DisposableEffect(win, full) {
            win?.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
            val c = win?.let { w -> WindowCompat.getInsetsController(w, dview) }
            if (full) { c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c?.hide(WindowInsetsCompat.Type.systemBars()) }
            else c?.show(WindowInsetsCompat.Type.systemBars())
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0D0F13))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { list[it].id }) { page ->
                val it = list[page]
                val current = pager.currentPage == page
                LaunchedEffect(current) { if (current) { zoomed = false; Session.logView(it.id) } }
                val toggle = { chrome = !chrome }
                when (kindOf(it)) {
                    Kind.IMG -> ImagePage(v, it, current, zoomCmd, toggle) { z -> if (current) zoomed = z }
                    Kind.VID, Kind.AUD -> MediaPage(v, it, current)
                    Kind.PDF -> PdfPage(v, it, zoomCmd.takeIf { current }, toggle) { z -> if (current) zoomed = z }
                    Kind.DOCX -> DocxPage(v, it)
                    Kind.TXT, Kind.CSV -> TextPage(v, it, zoomCmd.takeIf { current }, toggle)
                    null -> OtherPage(it)
                }
            }
            val it = list[pager.currentPage]
            if (chrome) Row(Modifier.fillMaxWidth().background(Color(0xCC0D0F13)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(it.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (list.size > 1) Text("${pager.currentPage + 1} / ${list.size}", color = Color(0xFFAAAABB), fontSize = 12.sp)
                }
                val k = kindOf(it)
                if (k == Kind.IMG || k == Kind.PDF || k == Kind.TXT || k == Kind.CSV) {
                    IconButton({ zoomCmd = (zoomCmd.first + 1) to -1 }) { Icon(Icons.Default.ZoomOut, "Zoom out", tint = Color.White) }
                    IconButton({ zoomCmd = (zoomCmd.first + 1) to 1 }) { Icon(Icons.Default.ZoomIn, "Zoom in", tint = Color.White) }
                }
                IconButton({ full = !full }) { Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen", tint = Color.White) }
                var m by remember { mutableStateOf(false) }
                Box {
                    IconButton({ m = true }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(m, { m = false }) {
                        DropdownMenuItem({ Text("Open with…") }, { m = false; openWith(ctx, it) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
                        DropdownMenuItem({ Text("Share") }, { m = false; share(ctx, listOf(it)) }, leadingIcon = { Icon(Icons.Default.Share, null) })
                        DropdownMenuItem({ Text("Copy to phone") }, { m = false; exportToPhone(listOf(it.id)) }, leadingIcon = { Icon(Icons.Default.Download, null) })
                    }
                }
            }
        }
    }
}

// ---------------- zoom helper used by photos and PDFs
@Composable
private fun rememberZoom(cmd: Pair<Int, Int>?, max: Float, onZoom: (Boolean) -> Unit): Triple<MutableFloatState, MutableState<Offset>, Modifier> {
    val scale = remember { mutableFloatStateOf(1f) }
    val off = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(cmd) {
        if (cmd != null && cmd.first > 0) {
            scale.floatValue = (scale.floatValue * if (cmd.second > 0) 1.5f else 1 / 1.5f).coerceIn(1f, max)
            if (scale.floatValue == 1f) off.value = Offset.Zero
            onZoom(scale.floatValue > 1.01f)
        }
    }
    // pinch always zooms; one-finger drags pan only while zoomed (otherwise they swipe pages / scroll)
    val mod = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val ev = awaitPointerEvent()
                val multi = ev.changes.count { c -> c.pressed } > 1
                if (multi || scale.floatValue > 1.01f) {
                    val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val centroid = ev.calculateCentroid()
                    val ns = (scale.floatValue * zoom).coerceIn(1f, max)
                    off.value = if (ns <= 1.01f) Offset.Zero else off.value + pan + (centroid - Offset(size.width / 2f, size.height / 2f)) * (1 - zoom)
                    scale.floatValue = ns; onZoom(ns > 1.01f)
                    ev.changes.forEach { c -> if (c.positionChanged()) c.consume() }
                }
            } while (ev.changes.any { c -> c.pressed })
        }
    }
    return Triple(scale, off, mod)
}

@Composable
private fun ImagePage(v: Vault, it: Item, current: Boolean, cmd: Pair<Int, Int>, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    val maxDim = 4096
    LaunchedEffect(it.id) {
        bmp = withContext(Dispatchers.IO) {
            try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                v.Reader(it.id).use { r -> BitmapFactory.decodeStream(r.stream(), null, o) }
                var s = 1; while (o.outWidth / s > maxDim || o.outHeight / s > maxDim) s *= 2
                v.Reader(it.id).use { r -> BitmapFactory.decodeStream(r.stream(), null, BitmapFactory.Options().apply { inSampleSize = s }) }
            } catch (e: Throwable) { null }
        }
        failed = bmp == null
    }
    val (scale, off, mod) = rememberZoom(if (current) cmd else null, 8f, onZoom)
    Box(Modifier.fillMaxSize().then(mod).pointerInput(Unit) {
        detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
            if (scale.floatValue > 1.01f) { scale.floatValue = 1f; off.value = Offset.Zero; onZoom(false) }
            else { scale.floatValue = 2.5f; off.value = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f; onZoom(true) }
        })
    }, contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(b.asImageBitmap(), it.name, Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale.floatValue; scaleY = scale.floatValue; translationX = off.value.x; translationY = off.value.y }, contentScale = ContentScale.Fit)
            failed -> Text("Can't show this image here. Use Open with…", color = Color.LightGray)
            else -> CircularProgressIndicator()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPage(v: Vault, it: Item, current: Boolean) {
    val ctx = LocalContext.current
    if (!current) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(iconFor(it), null, Modifier.size(64.dp), tint = Color.Gray) }; return }
    val player = remember(it.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory { VaultDataSource(v, it.id) }.createMediaSource(MediaItem.fromUri("dv://${it.id}/${android.net.Uri.encode(it.name)}")))
            prepare(); playWhenReady = true
        }
    }
    var err by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { err = "This phone can't play this file (${error.errorCodeName}). Try Open with… → VLC." }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val audio = tracks.groups.filter { g -> g.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty() && audio.none { g -> g.isSupported }) err = "No sound: this phone has no decoder for this audio format (e.g. DD5.1). Open with… → VLC plays it."
            }
        }
        player.addListener(l)
        Session.logItem("streamed", it.id, "note" to "phone player")
        onDispose { player.removeListener(l); player.release() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ c -> PlayerView(c).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } }, Modifier.fillMaxSize())
        err?.let { e -> Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).navigationBarsPadding(), color = Color(0xCC000000), shape = MaterialTheme.shapes.medium) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(e, color = Color.White, modifier = Modifier.weight(1f).padding(12.dp), fontSize = 13.sp)
                TextButton({ openWith(ctx, it) }) { Text("Open with") } } } }
    }
}

@Composable
private fun PdfPage(v: Vault, it: Item, cmd: Pair<Int, Int>?, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val mutex = remember { Mutex() }
    val width = LocalConfiguration.current.screenWidthDp * 2 * ctx.resources.displayMetrics.density.coerceAtMost(2f)
    DisposableEffect(it.id) {
        var pfd: android.os.ParcelFileDescriptor? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(VaultProvider.uriFor(it.id, it.name), "r")
            renderer = PdfRenderer(pfd!!)
        } catch (e: Exception) { failed = "Can't show this PDF here. Use Open with…" }
        onDispose { try { renderer?.close() } catch (_: Exception) {}; try { pfd?.close() } catch (_: Exception) {} }
    }
    val (scale, off, mod) = rememberZoom(cmd, 5f, onZoom)
    val r = renderer
    if (r == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (failed != null) Text(failed!!, color = Color.LightGray) else CircularProgressIndicator() }; return }
    Box(Modifier.fillMaxSize().then(mod).pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        LazyColumn(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.floatValue; scaleY = scale.floatValue; translationX = off.value.x; translationY = off.value.y },
            contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(r.pageCount) { i ->
                var bmp by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(i) {
                    bmp = withContext(Dispatchers.IO) {
                        mutex.withLock {
                            r.openPage(i).use { p ->
                                val w = width.toInt(); val h = (w.toFloat() * p.height / p.width).toInt()
                                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            }
                        }
                    }
                }
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color(0xFF222222)))
            }
        }
    }
}

@Composable
private fun TextPage(v: Vault, it: Item, cmd: Pair<Int, Int>?, onTap: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var font by remember { mutableFloatStateOf(14f) }
    LaunchedEffect(cmd) { if (cmd != null && cmd.first > 0) font = (font * if (cmd.second > 0) 1.2f else 1 / 1.2f).coerceIn(8f, 40f) }
    LaunchedEffect(it.id) {
        text = withContext(Dispatchers.IO) {
            v.Reader(it.id).use { r -> val n = minOf(r.size, 2L shl 20).toInt(); val b = ByteArray(n); var p = 0
                while (p < n) { val k = r.read(p.toLong(), b, p, n - p); if (k <= 0) break; p += k }
                String(b, 0, p, Charsets.UTF_8) + if (r.size > n) "\n\n… (first 2 MB shown)" else "" }
        }
    }
    val t = text ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    Surface(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp), color = MaterialTheme.colorScheme.surface) {
        if (kindOf(it) == Kind.CSV) {
            val sep = if (it.name.endsWith(".tsv", true)) '\t' else ','
            val rows = remember(t) { parseCsv(t, sep).take(2000) }
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                LazyColumn(Modifier.padding(8.dp)) {
                    items(rows.size) { ri -> Row { rows[ri].forEach { c -> Text(c, Modifier.width(140.dp).padding(4.dp), fontSize = font.sp, maxLines = 2) } }; HorizontalDivider() }
                }
            }
        } else SelectionContainer { Text(t, Modifier.verticalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = font.sp) }
    }
}

private fun parseCsv(text: String, sep: Char): List<List<String>> {
    val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val f = StringBuilder(); var q = false; var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (q) { if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(ch) }
        else when (ch) { '"' -> q = true; sep -> { row.add(f.toString()); f.clear() }; '\n' -> { row.add(f.toString().trimEnd('\r')); f.clear(); rows.add(row); row = ArrayList() }; else -> f.append(ch) }
        i++
    }
    if (f.isNotEmpty() || row.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
    return rows
}

class DocBridge(private val data: String) { @JavascriptInterface fun doc(): String = data }

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun DocxPage(v: Vault, it: Item) {
    var b64 by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(it.id) { b64 = withContext(Dispatchers.IO) { if (it.size > 40_000_000) "" else Base64.encodeToString(v.readAll(it.id), Base64.NO_WRAP) } }
    val data = b64 ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    if (data.isEmpty()) return OtherPage(it)
    AndroidView({ c ->
        WebView(c).apply {
            settings.javaScriptEnabled = true; settings.blockNetworkLoads = true; settings.allowContentAccess = false
            settings.builtInZoomControls = true; settings.displayZoomControls = false
            webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true }
            addJavascriptInterface(DocBridge(data), "DV")
            loadDataWithBaseURL("file:///android_asset/", """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <style>body{font:16px/1.55 sans-serif;margin:70px 16px 30px;color:#222;background:#fff}img{max-width:100%}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px}</style>
                <body>Loading…<script src="desktop/app/mammoth.min.js"></script><script>
                const s=atob(DV.doc()),u=new Uint8Array(s.length);for(let i=0;i<s.length;i++)u[i]=s.charCodeAt(i);
                mammoth.convertToHtml({arrayBuffer:u.buffer}).then(r=>document.body.innerHTML=r.value).catch(()=>document.body.textContent='Could not read this document.');
                </script>""", "text/html", "utf-8", null)
        }
    }, Modifier.fillMaxSize())
}

@Composable
private fun OtherPage(it: Item) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(iconFor(it), null, Modifier.size(72.dp), tint = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Text("No preview for this file type.", color = Color.LightGray)
        Spacer(Modifier.height(12.dp))
        Button({ openWith(ctx, it) }) { Text("Open with…") }
    }
}
