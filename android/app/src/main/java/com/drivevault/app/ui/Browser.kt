package com.drivevault.app.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.drivevault.app.*
import com.drivevault.core.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** Browser navigation state survives switching to History / Security. */
object Nav {
    var cur by mutableStateOf(ROOT)
    val back = mutableStateListOf<String>()
    val fwd = mutableStateListOf<String>()
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    val sel = mutableStateListOf<String>()
    fun go(id: String, record: Boolean = true) {
        if (record && id != cur) { back.add(cur); fwd.clear() }
        cur = id; sel.clear(); searching = false; query = ""
    }
    fun goBack() { if (back.isNotEmpty()) { fwd.add(cur); cur = back.removeAt(back.lastIndex); sel.clear() } }
    fun goFwd() { if (fwd.isNotEmpty()) { back.add(cur); cur = fwd.removeAt(fwd.lastIndex); sel.clear() } }
}

enum class Kind { IMG, VID, AUD, PDF, DOCX, TXT, CSV }
fun kindOf(it: Item): Kind? {
    if (it.isDir) return null
    val ext = it.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif" -> Kind.IMG
        "mp4", "mkv", "webm", "mov", "m4v", "3gp", "avi", "ts" -> Kind.VID
        "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> Kind.AUD
        "pdf" -> Kind.PDF
        "docx" -> Kind.DOCX
        "csv", "tsv" -> Kind.CSV
        "txt", "md", "log", "json", "xml", "yml", "yaml", "ini", "conf", "py", "js", "html", "htm", "css", "sh", "bat", "kt", "java", "c", "cpp", "sql", "srt" -> Kind.TXT
        else -> null
    }
}

fun iconFor(it: Item): ImageVector = when (kindOf(it)) {
    null -> if (it.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile
    Kind.IMG -> Icons.Default.Image; Kind.VID -> Icons.Default.Movie; Kind.AUD -> Icons.Default.MusicNote
    else -> Icons.Default.Description
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(act: ComponentActivity) {
    val ctx = LocalContext.current
    val v = Session.vault ?: return
    if (!v.unlocked) { Session.screen = Screen.Lock; return }
    val ver = Session.version
    var sortK by remember { mutableStateOf(Session.prefs.getString("sort_k", "n")!!) }
    var sortD by remember { mutableIntStateOf(Session.prefs.getInt("sort_d", 1)) }
    fun setSort(k: String) { sortD = if (sortK == k && k != "o") -sortD else 1; sortK = k; Session.prefs.edit().putString("sort_k", sortK).putInt("sort_d", sortD).apply() }
    var gridView by remember { mutableStateOf(Session.prefs.getBoolean("grid", false)) }

    if (Nav.cur != ROOT && Nav.cur != TRASH && Nav.cur !in v.entries) Nav.cur = ROOT
    val inTrash = Nav.cur == TRASH || (Nav.cur != ROOT && v.inTrash(Nav.cur))
    val trashTop = Nav.cur == TRASH && !Nav.searching
    val custom = sortK == "o" && !Nav.searching && !inTrash

    val items: List<Item> = remember(ver, Nav.cur, Nav.searching, Nav.query, sortK, sortD) {
        var r: List<Item> = emptyList()
        for (attempt in 0..3) try {
            r = v.withLock { if (Nav.searching && Nav.query.isNotBlank()) v.search(Nav.query) else v.children(Nav.cur) }
            break
        } catch (e: ConcurrentModificationException) {}
        val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val k = if (sortK == "o" && !custom) "n" else sortK
        val inner: Comparator<Item> = when (k) {
            "o" -> compareBy<Item> { it.order ?: Double.MAX_VALUE }.then(byName)
            "s" -> compareBy<Item> { it.size }.let { if (sortD < 0) it.reversed() else it }
            "m" -> compareBy<Item> { it.mtime }.let { if (sortD < 0) it.reversed() else it }
            else -> if (sortD < 0) byName.reversed() else byName
        }
        r.sortedWith(compareBy<Item> { if (it.isDir) 0 else 1 }.then(inner))
    }
    val path = remember(ver, Nav.cur) { try { v.pathOf(Nav.cur).let { if (it.first().id == TRASH) listOf(Vault.Crumb(ROOT, "Vault")) + it else it } } catch (e: Exception) { listOf(Vault.Crumb(ROOT, "Vault")) } }
    val parentId = if (path.size > 1 && !Nav.searching) path[path.size - 2].id else null

    // dialogs / overlays
    var viewer by remember { mutableStateOf<Pair<List<Item>, Int>?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renameItem by remember { mutableStateOf<Item?>(null) }
    var confirmPurge by remember { mutableStateOf<List<String>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var moveIds by remember { mutableStateOf<List<String>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var recLater by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importSrcs(Storage.fromUris(ctx, uris), Nav.cur)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Session.io { val s = Storage.fromTree(ctx, uri); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { importSrcs(s, Nav.cur) } }
    }

    BackHandler(enabled = viewer != null || Nav.sel.isNotEmpty() || Nav.searching || Nav.back.isNotEmpty() || Nav.cur != ROOT) {
        when {
            viewer != null -> viewer = null
            Nav.sel.isNotEmpty() -> Nav.sel.clear()
            Nav.searching -> { Nav.searching = false; Nav.query = "" }
            Nav.back.isNotEmpty() -> Nav.goBack()
            else -> Nav.go(ROOT)
        }
    }

    val selItems = items.filter { it.id in Nav.sel }

    Scaffold(
        topBar = {
            if (Nav.sel.isNotEmpty()) TopAppBar(
                title = { Text("${Nav.sel.size} selected") },
                navigationIcon = { IconButton({ Nav.sel.clear() }) { Icon(Icons.Default.Close, "Clear selection") } },
                actions = {
                    IconButton({ Nav.sel.clear(); Nav.sel.addAll(items.map { it.id }) }) { Icon(Icons.Default.SelectAll, "Select all") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer))
            else if (Nav.searching) TopAppBar(
                title = { OutlinedTextField(Nav.query, { Nav.query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search the whole vault") }, singleLine = true) },
                navigationIcon = { IconButton({ Nav.searching = false; Nav.query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } })
            else TopAppBar(
                title = { Text(if (trashTop) "Trash" else path.last().name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton({ Nav.go(ROOT) }) { Icon(Icons.Default.Home, "Home") } },
                actions = {
                    IconButton({ Nav.searching = true }) { Icon(Icons.Default.Search, "Search") }
                    IconButton({ gridView = !gridView; Session.prefs.edit().putBoolean("grid", gridView).apply() }) {
                        Icon(if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, if (gridView) "List view" else "Grid view") }
                    if (!inTrash) Box {
                        IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            for ((k, l) in listOf("n" to "Name", "s" to "Size", "m" to "Modified", "o" to "Custom order"))
                                DropdownMenuItem({ Text(l + if (sortK == k && k != "o") (if (sortD > 0) "  ↑" else "  ↓") else "") }, { setSort(k); sortMenu = false },
                                    leadingIcon = { RadioButton(sortK == k, null) })
                        }
                    }
                    if (trashTop && items.isNotEmpty()) TextButton({ confirmEmpty = true }) { Text("Empty") }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Trash" + if (v.trashCount() > 0) " (${v.trashCount()})" else "") }, { menu = false; Nav.go(TRASH) }, leadingIcon = { Icon(Icons.Default.Delete, null) })
                            DropdownMenuItem({ Text("History") }, { menu = false; Session.screen = Screen.History }, leadingIcon = { Icon(Icons.Default.History, null) })
                            DropdownMenuItem({ Text("Security") }, { menu = false; Session.screen = Screen.Security }, leadingIcon = { Icon(Icons.Default.Shield, null) })
                            DropdownMenuItem({ Text("Activity log (plain)") }, { menu = false; showLog = true }, leadingIcon = { Icon(Icons.Default.Article, null) })
                            DropdownMenuItem({ Text("Switch drive") }, { menu = false; Session.lock("switch drive"); Session.screen = Screen.Locations }, leadingIcon = { Icon(Icons.Default.Usb, null) })
                            HorizontalDivider()
                            DropdownMenuItem({ Text("Lock") }, { menu = false; Session.lock() }, leadingIcon = { Icon(Icons.Default.Lock, null) })
                        }
                    }
                })
        },
        bottomBar = {
            if (Nav.sel.isNotEmpty()) BottomAppBar {
                if (trashTop) {
                    TextButton({ val ids = Nav.sel.toList(); Session.io { v.restore(ids); ids.forEach { Session.logItem("restored", it) } }; Nav.sel.clear() }) {
                        Icon(Icons.Default.RestoreFromTrash, null); Spacer(Modifier.width(6.dp)); Text("Restore") }
                    Spacer(Modifier.weight(1f))
                    TextButton({ confirmPurge = Nav.sel.toList() }) { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(6.dp)); Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                } else if (!inTrash) {
                    BarAction(Icons.AutoMirrored.Filled.DriveFileMove, "Move", Modifier.weight(1f)) { moveIds = Nav.sel.toList() }
                    BarAction(Icons.Default.Download, "To phone", Modifier.weight(1f)) { exportToPhone(selItems.map { it.id }) }
                    BarAction(Icons.Default.Share, "Share", Modifier.weight(1f)) { share(ctx, selItems.filter { !it.isDir }) }
                    BarAction(Icons.Default.Delete, "Trash", Modifier.weight(1f)) { trashIds(Nav.sel.toList()) }
                    Box(Modifier.weight(1f)) {
                        BarAction(Icons.Default.MoreHoriz, "More", Modifier.fillMaxWidth()) { moreMenu = true }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            if (Nav.sel.size == 1) DropdownMenuItem({ Text("Rename") }, { moreMenu = false; renameItem = selItems.firstOrNull() }, leadingIcon = { Icon(Icons.Default.Edit, null) })
                            if (selItems.size == 1 && !selItems[0].isDir) DropdownMenuItem({ Text("Open with…") }, { moreMenu = false; openWith(ctx, selItems[0]) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
                            if (custom) {
                                HorizontalDivider()
                                for ((w, l) in listOf("top" to "Move to top", "up" to "Move up", "down" to "Move down", "bottom" to "Move to bottom"))
                                    DropdownMenuItem({ Text(l) }, { moreMenu = false; nudge(v, items, w) })
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (Nav.sel.isEmpty() && !inTrash && !Nav.searching) FloatingActionButton({ addMenu = true }) { Icon(Icons.Default.Add, "Add") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // navigation row: back / forward / up + breadcrumb
            if (!Nav.searching) Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ Nav.goBack() }, enabled = Nav.back.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                IconButton({ Nav.goFwd() }, enabled = Nav.fwd.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                IconButton({ parentId?.let { Nav.go(it) } }, enabled = parentId != null) { Icon(Icons.Default.ArrowUpward, "Up") }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    path.forEachIndexed { i, c ->
                        if (i > 0) Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton({ Nav.go(c.id) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(c.name, fontWeight = if (i == path.lastIndex) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
            }
            val rec = remember(ver) { v.recoveryInfo() }
            if (!rec.key && rec.questions == null && rec.device == null && !recLater) Banner("No password recovery is set up. If the password is forgotten, the files are lost.", "Set up", { Session.screen = Screen.Security }) { recLater = true }
            if (Session.pendingShares.isNotEmpty() && !inTrash) Banner("${Session.pendingShares.size} shared file(s) waiting.", "Add here", {
                val list = Storage.fromUris(ctx, Session.pendingShares.toList()); Session.pendingShares.clear(); importSrcs(list, Nav.cur)
            }) { Session.pendingShares.clear() }
            if (custom) Text(if (gridView) "Custom order: select items, then More → Move up / down. Switch to list view to drag them."
                else "Custom order: drag ≡ to arrange (folders stay on top). Moving into folders: select, then Move.",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // ---------- list with drag-to-reorder in custom mode
            var dragId by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var rowH by remember { mutableIntStateOf(1) }
            var live by remember { mutableStateOf<List<Item>?>(null) }
            val shown = live ?: items
            val curShown by rememberUpdatedState(shown)
            val listState = rememberLazyListState()
            val haptic = LocalHapticFeedback.current
            val edge = with(LocalDensity.current) { 72.dp.toPx() }
            // swap the dragged row with its neighbour once it has moved half a row (only within its group: folders / files)
            fun settle() {
                val list = (live ?: return).toMutableList()
                while (true) {
                    val i = list.indexOfFirst { x -> x.id == dragId }; if (i < 0) return
                    if (dragOffset > rowH / 2f && i + 1 < list.size && list[i + 1].isDir == list[i].isDir) { java.util.Collections.swap(list, i, i + 1); dragOffset -= rowH }
                    else if (dragOffset < -rowH / 2f && i > 0 && list[i - 1].isDir == list[i].isDir) { java.util.Collections.swap(list, i, i - 1); dragOffset += rowH }
                    else break
                }
                live = list
            }
            // while dragging near the top or bottom edge, scroll the list and keep the row under the finger
            LaunchedEffect(dragId) {
                while (dragId != null) {
                    val li = listState.layoutInfo
                    li.visibleItemsInfo.firstOrNull { x -> x.key == dragId }?.let { info ->
                        val top = info.offset + dragOffset; val bottom = top + info.size
                        val step = when { bottom > li.viewportEndOffset - edge -> 14f; top < li.viewportStartOffset + edge -> -14f; else -> 0f }
                        if (step != 0f) { val used = listState.scrollBy(step); if (used != 0f) { dragOffset += used; settle() } }
                    }
                    kotlinx.coroutines.delay(16)
                }
            }

            if (shown.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(when { Nav.searching -> "Nothing found."; trashTop -> "Trash is empty."; else -> "This folder is empty.\nTap + to add files." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            val open: (Item) -> Unit = { it ->
                if (Nav.sel.isNotEmpty()) { if (it.id in Nav.sel) Nav.sel.remove(it.id) else Nav.sel.add(it.id) }
                else if (it.isDir) Nav.go(it.id)
                else { val pv = shown.filter { x -> kindOf(x) != null }; viewer = if (kindOf(it) != null) pv to pv.indexOf(it) else listOf(it) to 0 }
            }
            val toggle: (Item) -> Unit = { it -> if (it.id in Nav.sel) Nav.sel.remove(it.id) else Nav.sel.add(it.id) }
            if (gridView) LazyVerticalGrid(GridCells.Adaptive(112.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 96.dp)) {
                items(shown.size, key = { i -> shown[i].id }) { i -> Tile(v, shown[i], shown[i].id in Nav.sel, Nav.sel.isNotEmpty(), { open(shown[i]) }, { toggle(shown[i]) }) }
            }
            else LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 96.dp)) {
                items(shown, key = { it.id }) { it ->
                    val selected = it.id in Nav.sel
                    val dragging = dragId == it.id
                    Row(Modifier.fillMaxWidth()
                        .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .then(if (dragging) Modifier.shadow(8.dp) else Modifier)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                        .onSizeChanged { s -> rowH = s.height }
                        .combinedClickable(onClick = { open(it) }, onLongClick = { toggle(it) })
                        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (Nav.sel.isNotEmpty()) Checkbox(selected, { c -> if (c) Nav.sel.add(it.id) else Nav.sel.remove(it.id) })
                        Icon(iconFor(it), null, tint = if (it.isDir) Color(0xFFE8B23A) else MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = buildString {
                                if (Nav.searching) append("in ${v.where(it.e.parent)} · ")
                                if (trashTop) append("from ${it.e.origParent?.let { op -> v.where(op).ifEmpty { "Vault" } } ?: "Vault"} · deleted ${fmtDate(it.deletedAt ?: 0)}")
                                else { if (!it.isDir) append(fmtSize(it.size) + " · "); append(fmtDate(it.mtime)) }
                            }
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (custom) Icon(Icons.Default.DragHandle, "Drag to reorder", Modifier.padding(start = 8.dp).size(28.dp).pointerInput(it.id) {
                            detectDragGestures(
                                onDragStart = { _ -> dragId = it.id; dragOffset = 0f; live = curShown; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                onDrag = { change, amount -> change.consume(); dragOffset += amount.y; settle() },
                                onDragEnd = { commitOrder(v, live, dragId); dragId = null; dragOffset = 0f; live = null },
                                onDragCancel = { commitOrder(v, live, dragId); dragId = null; dragOffset = 0f; live = null })
                        }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (Nav.sel.isEmpty() && !it.isDir && !inTrash) IconButton({ openWith(ctx, it) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open with", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    if (newFolder) InputDialog("New folder", "Folder name", "New folder", "Create", onDismiss = { newFolder = false }) { n ->
        val id = v.mkdir(Nav.cur, n); Session.logItem("folder_created", id); Session.changed(); null }
    renameItem?.let { it -> InputDialog("Rename", "New name", it.name, "Rename", onDismiss = { renameItem = null }) { n ->
        val old = it.name; v.rename(it.id, n); Session.logItem("renamed", it.id, "from" to old); Nav.sel.clear(); Session.changed(); null } }
    confirmPurge?.let { ids -> ConfirmDialog("Delete ${ids.size} item(s) forever?", "This cannot be undone. The encrypted data is removed from the drive.", "Delete forever", true, { confirmPurge = null }) {
        purge(v, ids, "Deleting forever", false); Nav.sel.clear() } }
    if (confirmEmpty) ConfirmDialog("Empty trash?", "${items.size} item(s) will be deleted forever. This cannot be undone.", "Empty trash", true, { confirmEmpty = false }) {
        purge(v, v.emptyTrashIds(), "Emptying trash", true) }
    moveIds?.let { ids -> MoveDialog(v, ids) { moveIds = null } }
    if (showLog) PlainLogDialog { showLog = false }
    if (addMenu) AlertDialog(onDismissRequest = { addMenu = false }, title = { Text("Add to this folder") }, text = {
        Column {
            ListItem({ Text("Add files") }, Modifier.combinedClickableSimple { addMenu = false; Session.awayOk = true; pickFiles.launch(arrayOf("*/*")) },
                supportingContent = { Text("Photos, videos, documents… they're encrypted onto the drive") }, leadingContent = { Icon(Icons.Default.UploadFile, null) })
            ListItem({ Text("Add a folder") }, Modifier.combinedClickableSimple { addMenu = false; Session.awayOk = true; pickTree.launch(null) },
                supportingContent = { Text("Everything inside it, keeping the folder structure") }, leadingContent = { Icon(Icons.Default.DriveFolderUpload, null) })
            ListItem({ Text("New folder") }, Modifier.combinedClickableSimple { addMenu = false; newFolder = true }, leadingContent = { Icon(Icons.Default.CreateNewFolder, null) })
        }
    }, confirmButton = { TextButton({ addMenu = false }) { Text("Cancel") } })
    viewer?.let { (list, start) -> ViewerScreen(act, v, list, start) { viewer = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(v: Vault, it: Item, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val key = remember(it.id, Session.version) { Thumbs.key(v, it.id) }
    val thumb by produceState(Thumbs.cached(key), key) {
        if (value == null && kindOf(it) == Kind.IMG) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { Thumbs.load(v, it.id, key) } catch (e: Throwable) { null } }
    }
    Column(Modifier.padding(4.dp).clip(MaterialTheme.shapes.medium)
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { b -> Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Icon(iconFor(it), null, Modifier.size(40.dp), tint = if (it.isDir) Color(0xFFE8B23A) else MaterialTheme.colorScheme.primary)
            if (selecting) Checkbox(selected, { onLongClick() }, Modifier.align(Alignment.TopStart))
        }
        Text(it.name, Modifier.fillMaxWidth().padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.combinedClickableSimple(onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label); Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimple(onClick: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick))

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onAction) { Text(action) }
            IconButton(onDismiss) { Icon(Icons.Default.Close, "Dismiss") }
        }
    }
}

// ---------------- actions
private fun commitOrder(v: Vault, live: List<Item>?, dragId: String?) {
    val list = live ?: return; val d = list.firstOrNull { it.id == dragId } ?: return
    val ids = list.filter { it.isDir == d.isDir }.map { it.id }
    val parent = Nav.cur
    Session.io { v.reorder(parent, ids); Session.log("reordered", "path" to v.where(parent), "files" to ids.size) }
}

private fun nudge(v: Vault, items: List<Item>, where: String) {
    val picked = Nav.sel.toSet(); val parent = Nav.cur
    Session.io {
        for (dir in listOf(true, false)) {
            val group = items.filter { it.isDir == dir }.map { it.id }
            if (group.none { it in picked }) continue
            val order = when (where) {
                "top" -> group.filter { it in picked } + group.filter { it !in picked }
                "bottom" -> group.filter { it !in picked } + group.filter { it in picked }
                else -> group.toMutableList().also { o ->
                    val idxs = o.indices.filter { o[it] in picked }.let { if (where == "down") it.reversed() else it }
                    for (i in idxs) { val j = if (where == "up") i - 1 else i + 1; if (j in o.indices && o[j] !in picked) java.util.Collections.swap(o, i, j) }
                }
            }
            v.reorder(parent, order)
        }
        Session.log("reordered", "path" to v.where(parent), "files" to picked.size)
    }
}

private fun trashIds(ids: List<String>) {
    val v = Session.vault ?: return
    Session.io { ids.forEach { Session.logItem("trashed", it) }; v.trash(ids) }
    Nav.sel.clear()
    Session.say("Moved ${ids.size} item(s) to Trash. Restore them from the menu → Trash.")
}

private fun purge(v: Vault, ids: List<String>, label: String, empty: Boolean) {
    val (size, files) = ids.fold(0L to 0) { acc, id -> try { val (s, f) = v.treeSize(id); acc.first + s to acc.second + f } catch (e: Exception) { acc } }
    if (!empty) ids.forEach { Session.logItem("deleted", it) }
    Session.work(label, files.toLong().coerceAtLeast(1), "files", { j ->
        v.delete(ids) { j.add(it.toLong()) }
        if (empty) Session.log("trash_emptied", "files" to files, "size" to size, "note" to "${ids.size} item(s)")
    })
}

fun importSrcs(srcs: List<Storage.Src>, parent: String) {
    val v = Session.vault ?: return
    if (srcs.isEmpty()) return
    Session.work("Encrypting ${srcs.count { it.name.isNotEmpty() }} file(s)", srcs.sumOf { it.size }, "bytes", { j -> Storage.import(v, parent, srcs, j) }) {
        Session.say("Encrypted ${srcs.count { it.name.isNotEmpty() }} file(s) into the vault.")
    }
}

fun exportToPhone(ids: List<String>) {
    val v = Session.vault ?: return
    val dest = File(Storage.downloadsDir(), "DriveVault export " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date()))
    val total = ids.sumOf { v.treeSize(it).first }
    Session.work("Copying ${ids.size} item(s) to this phone", total, "bytes", { j ->
        dest.mkdirs()
        val st = android.os.StatFs(dest.absolutePath)
        if (st.availableBytes < total + (100L shl 20)) { dest.deleteRecursively(); throw VaultException("Not enough space on the phone: needs ${fmtSize(total)}, ${fmtSize(st.availableBytes)} free.") }
        try { for (id in ids) { v.export(id, dest, { j.add(it) }, unique = true); Session.logItem("exported", id, "dest" to dest.absolutePath) } }
        catch (e: Exception) { dest.deleteRecursively(); throw e }
    }) { Session.say("Copied to Downloads/${dest.name}") }
    Nav.sel.clear()
}

fun share(ctx: android.content.Context, files: List<Item>) {
    if (files.isEmpty()) return Session.say("Select files (not folders) to share.")
    val uris = ArrayList(files.map { VaultProvider.uriFor(it.id, it.name) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(VaultProvider.mime(files[0].name))
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*")
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    files.forEach { Session.logItem("shared", it.id) }
    Session.awayOk = true
    ctx.startActivity(Intent.createChooser(i, "Share decrypted copy"))
    Session.say("The other app reads the file while DriveVault is unlocked; links stop working when you lock.")
}

fun openWith(ctx: android.content.Context, it: Item) {
    val uri = VaultProvider.uriFor(it.id, it.name)
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, VaultProvider.mime(it.name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    Session.logItem("opened", it.id, "note" to "streamed to another app")
    Session.awayOk = true
    try { ctx.startActivity(Intent.createChooser(i, "Open with")) } catch (e: Exception) { Session.say("No app can open this file type.") }
}

@Composable
fun MoveDialog(v: Vault, ids: List<String>, onDismiss: () -> Unit) {
    var at by remember { mutableStateOf(ROOT) }
    var err by remember { mutableStateOf<String?>(null) }
    var newF by remember { mutableStateOf(false) }
    val ver = Session.version
    val dirs = remember(at, ver) { v.withLock { v.children(at) }.filter { it.isDir }.sortedBy { it.name.lowercase() } }
    val crumbs = remember(at, ver) { v.pathOf(at) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Move ${ids.size} item(s) to…") },
        text = {
            Column {
                Row(Modifier.horizontalScroll(rememberScrollState())) { crumbs.forEachIndexed { i, c -> if (i > 0) Text(" › "); Text(c.name, Modifier.combinedClickableSimple { at = c.id }, fontWeight = if (i == crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal) } }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (dirs.isEmpty()) item { Text("No folders here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    items(dirs, key = { it.id }) { d ->
                        val disabled = d.id in ids
                        Row(Modifier.fillMaxWidth().combinedClickableSimple { if (!disabled) at = d.id }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, null, tint = Color(0xFFE8B23A).copy(alpha = if (disabled) .4f else 1f)); Spacer(Modifier.width(10.dp))
                            Text(d.name, color = if (disabled) MaterialTheme.colorScheme.outline else Color.Unspecified)
                        }
                    }
                }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton({
            try {
                for (i in ids) { val from = v.where(v.get(i).parent); v.move(i, at); Session.logItem("moved", i, "from" to from) }
                Nav.sel.clear(); Session.changed(); Session.say("Moved ${ids.size} item(s)."); onDismiss()
            } catch (e: Exception) { err = e.message }
        }) { Text("Move here") } },
        dismissButton = { Row { TextButton({ newF = true }) { Text("New folder") }; TextButton(onDismiss) { Text("Cancel") } } })
    if (newF) InputDialog("New folder", "Folder name", "New folder", "Create", onDismiss = { newF = false }) { n -> at = v.mkdir(at, n); Session.changed(); null }
}
