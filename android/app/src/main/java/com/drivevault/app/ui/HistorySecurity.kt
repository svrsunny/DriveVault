package com.drivevault.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivevault.app.*
import com.drivevault.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private val GROUPS = linkedMapOf(
    "" to ("All" to emptySet()),
    "added" to ("Added & saved" to setOf("encrypted", "added_back", "folder_created", "saved_back")),
    "out" to ("Opened & copied out" to setOf("opened", "revealed", "downloaded", "exported", "viewed", "streamed", "clipboard", "shared")),
    "removed" to ("Deleted & restored" to setOf("trashed", "restored", "deleted", "trash_emptied")),
    "changes" to ("Renamed & moved" to setOf("renamed", "moved", "reordered")),
    "access" to ("Access & security" to setOf("created", "unlocked", "locked", "unlock_failed", "recover_failed", "password_changed", "password_reset",
        "recovery_key_created", "recovery_key_removed", "questions_set", "questions_removed", "device_added", "device_removed", "settings", "history_exported")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val v = Session.vault ?: return
    if (!v.unlocked) { Session.screen = Screen.Lock; return }
    BackHandler { Session.screen = Screen.Browser }
    var group by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var data by remember { mutableStateOf<Vault.History?>(null) }
    var names by remember { mutableStateOf(v.settings()["plainLogNames"]?.jsonPrimitive?.booleanOrNull == true) }
    var confirmCsv by remember { mutableStateOf(false) }
    LaunchedEffect(Session.version) { data = withContext(Dispatchers.IO) { v.historyRead() } }
    val shown = remember(data, group, q) {
        val set = GROUPS[group]!!.second
        (data?.events ?: emptyList()).asReversed().filter { e ->
            (set.isEmpty() || e["ev"]?.jsonPrimitive?.content in set) && (q.isBlank() || e.toString().contains(q, ignoreCase = true))
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("History") }, navigationIcon = { IconButton({ Session.screen = Screen.Browser }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { TextButton({ confirmCsv = true }) { Text("Export CSV") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), placeholder = { Text("Search history") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
                for ((k, g) in GROUPS) FilterChip(group == k, { group = k }, { Text(g.first) }, Modifier.padding(horizontal = 3.dp))
            }
            val d = data
            if (d != null) {
                val ok = d.problems.isEmpty()
                Surface(color = if (ok) Color(0x221F8A4C) else MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = MaterialTheme.shapes.medium) {
                    Text(if (ok) "✓ Verified: ${d.events.size} events, all sealed, none missing or altered." else "⚠ History integrity problem: " + d.problems.joinToString(" "),
                        Modifier.padding(10.dp), color = if (ok) Color(0xFF1F8A4C) else MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("File names in the plain-text log", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(names, { on -> names = on; Session.io { v.setSetting("plainLogNames", JsonPrimitive(on)); Session.log("settings", "note" to "file names in plain log: " + if (on) "on" else "off") } })
            }
            if (d == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                items(shown) { e ->
                    fun f(k: String) = e[k]?.jsonPrimitive?.contentOrNull
                    val ev = f("ev") ?: ""
                    val label = (EVENT_TEXT[ev] ?: ev).lowercase().replaceFirstChar { it.uppercase() }
                    val extra = listOfNotNull(f("path")?.let { "in $it" }, f("from")?.let { "from $it" }, f("dest")?.let { "to $it" },
                        f("via")?.let { "via $it" }, f("why"), f("note")).joinToString(" · ")
                    ListItem(
                        overlineContent = { Text(fmtDate(e["t"]?.jsonPrimitive?.longOrNull ?: 0) + " · " + (f("host") ?: "") + (f("os")?.let { " · $it" } ?: "")) },
                        headlineContent = { Text(label + (f("name")?.let { ": $it" } ?: ""), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { if (extra.isNotEmpty() || e["size"] != null) Text(listOfNotNull(extra.ifEmpty { null }, e["size"]?.jsonPrimitive?.longOrNull?.let { fmtSize(it) }).joinToString(" · "), maxLines = 2) })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
    if (confirmCsv) ConfirmDialog("Export history as CSV?", "The CSV is a normal, unencrypted file saved to Downloads. It includes file names.", "Export", false, { confirmCsv = false }) {
        Session.io {
            val evs = v.historyRead().events
            val f = File(Storage.downloadsDir(), "DriveVault history " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date()) + ".csv")
            fun esc(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
            f.bufferedWriter().use { w ->
                w.write("﻿#,time,event,name,folder,from,to,size_bytes,files,details,computer,os,user\n")
                for (e in evs) {
                    fun g(k: String) = e[k]?.jsonPrimitive?.contentOrNull ?: ""
                    w.write(listOf(g("seq"), SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(e["t"]?.jsonPrimitive?.longOrNull ?: 0)),
                        EVENT_TEXT[g("ev")] ?: g("ev"), g("name"), g("path"), g("from"), g("dest"), g("size"), g("files"),
                        listOf(g("via"), g("why"), g("note")).filter { it.isNotEmpty() }.joinToString(" "), g("host"), g("os"), g("user")).joinToString(",") { esc(it) } + "\n")
                }
            }
            Session.log("history_exported", "note" to "${evs.size} events")
            Session.say("Saved Downloads/${f.name}")
        }
    }
}

private val QUESTIONS = listOf("What was the name of your first school?", "What is your oldest cousin's first name?", "What was the name of the street you grew up on?",
    "What was your childhood nickname?", "In which town was your mother born?", "What was the make and model of your first phone?",
    "What was the name of your first teacher?", "What was your favourite food as a child?", "What was the name of your first pet?")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen() {
    val ctx = LocalContext.current
    val v = Session.vault ?: return
    if (!v.unlocked) { Session.screen = Screen.Lock; return }
    BackHandler { Session.screen = Screen.Browser }
    val info = remember(Session.version) { v.recoveryInfo() }
    val thisPhone = info.deviceId == DeviceTrust.deviceId(ctx) && DeviceTrust.has(ctx, v.vaultId)
    var dlg by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf<String?>(null) }
    val screenLock = rememberScreenLockCheck()
    var devPw by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Security") }, navigationIcon = { IconButton({ Session.screen = Screen.Browser }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ListItem(headlineContent = { Text("Password") }, supportingContent = { Text("Used to unlock the vault.") },
                trailingContent = { OutlinedButton({ dlg = "pw" }) { Text("Change") } })
            HorizontalDivider()
            ListItem(headlineContent = { Text("Recovery key" + if (info.key) "  ✓" else "") }, supportingContent = { Text("A 25-character key that resets the password. The strongest recovery option.") },
                trailingContent = { Row { if (info.key) TextButton({ dlg = "rmkey" }) { Text("Remove", color = MaterialTheme.colorScheme.error) }; OutlinedButton({ dlg = "key" }) { Text(if (info.key) "Replace" else "Create") } } })
            HorizontalDivider()
            ListItem(headlineContent = { Text("Security questions" + if (info.questions != null) "  ✓" else "") },
                supportingContent = { Text(info.questions?.joinToString(" · ") ?: "Three questions only you can answer.") },
                trailingContent = { Row { if (info.questions != null) TextButton({ dlg = "rmq" }) { Text("Remove", color = MaterialTheme.colorScheme.error) }; OutlinedButton({ dlg = "q" }) { Text(if (info.questions != null) "Change" else "Set up") } } })
            HorizontalDivider()
            ListItem(headlineContent = { Text("Trusted phone" + if (info.device != null) "  ✓" else "") },
                supportingContent = { Text(when {
                    thisPhone -> "This phone (${info.device}). If the password is forgotten, plug the drive into this phone, tap Forgot password and confirm your fingerprint / PIN."
                    info.device != null -> "Another phone (${info.device}) is trusted. Making this phone trusted replaces it."
                    else -> "Reset a forgotten password from this phone using its fingerprint / PIN. Only this phone can do it." }) },
                trailingContent = { Row {
                    if (info.device != null) TextButton({ dlg = "rmdev" }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    if (!thisPhone) OutlinedButton({ if (!DeviceTrust.secure(ctx)) Session.say("Set a screen lock (PIN, pattern or fingerprint) on this phone first.") else dlg = "dev" }) { Text("Trust this phone") }
                } })
            HorizontalDivider()
            ListItem(headlineContent = { Text("Auto-lock") }, supportingContent = { Text("Lock the vault when DriveVault has been in the background for:") })
            AutoLockChips(Modifier.padding(horizontal = 12.dp))
            Text("Screenshots and the recent-apps preview are blocked while DriveVault is open.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    when (dlg) {
        "pw" -> ChangePasswordDialog(v) { dlg = null }
        "key" -> InputDialog("Create a recovery key", "Current password", ok = "Create key", password = true, onDismiss = { dlg = null }) { p ->
            val k = v.setRecoveryKey(p); Session.log("recovery_key_created"); withContext(Dispatchers.Main) { showKey = k; Session.changed() }; null }
        "rmkey" -> InputDialog("Remove recovery key?", "Current password", ok = "Remove", password = true, onDismiss = { dlg = null }) { p ->
            v.removeRecovery(p, "key"); Session.log("recovery_key_removed"); Session.changed(); null }
        "rmq" -> InputDialog("Remove security questions?", "Current password", ok = "Remove", password = true, onDismiss = { dlg = null }) { p ->
            v.removeRecovery(p, "questions"); Session.log("questions_removed"); Session.changed(); null }
        "rmdev" -> InputDialog("Remove trusted phone?", "Current password", ok = "Remove", password = true, onDismiss = { dlg = null }) { p ->
            v.removeRecovery(p, "device"); v.vaultId?.let { DeviceTrust.forget(ctx, it) }; Session.log("device_removed"); Session.changed(); null }
        "q" -> QuestionsDialog(v) { dlg = null }
        "dev" -> InputDialog("Trust this phone", "Current vault password", ok = "Next", password = true, onDismiss = { dlg = null }) { p ->
            v.verifyPassword(p); withContext(Dispatchers.Main) { devPw = p }; null }
    }
    LaunchedEffect(devPw) {
        val p = devPw ?: return@LaunchedEffect
        devPw = null
        Session.awayOk = true
        screenLock("Trust this phone for DriveVault") {
            Session.io {
                val id = v.vaultId ?: throw VaultException("Vault has no id.")
                val dk = DeviceTrust.createAndStore(ctx, id)
                v.setDevice(p, dk, Session.host, DeviceTrust.deviceId(ctx))
                Session.log("device_added", "note" to Session.host)
                Session.say("This phone is now trusted to reset the password.")
            }
        }
    }
    showKey?.let { RecoveryKeyDialog(it) { showKey = null } }
}

@Composable
private fun ChangePasswordDialog(v: Vault, onDismiss: () -> Unit) {
    var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val legacy = remember { v.kdfName() == "pbkdf2-sha256" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Change password") }, text = {
        Column {
            PasswordField(a, { a = it }, "Current password"); Spacer(Modifier.height(6.dp))
            PasswordField(b, { b = it }, "New password"); Spacer(Modifier.height(6.dp))
            PasswordField(c, { c = it }, "Confirm new password")
            if (legacy) Text("This upgrades the vault to stronger password protection (Argon2id). DriveVault 1.7 and older can't open it afterwards, " +
                "so update DriveVault on your computers' drives first.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }, confirmButton = { TextButton({
        if (b != c) { err = "New passwords do not match."; return@TextButton }
        busy = true
        scope.launch {
            err = withContext(Dispatchers.IO) { try { v.changePassword(a, b); Session.log("password_changed"); null } catch (e: Exception) { Session.friendlyError(e) } }
            busy = false; if (err == null) { Session.say("Password changed. Recovery options still work."); onDismiss() }
        }
    }, enabled = !busy) { Text("Change") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionsDialog(v: Vault, onDismiss: () -> Unit) {
    val qs = remember { mutableStateListOf(QUESTIONS[0], QUESTIONS[1], QUESTIONS[2]) }
    val ans = remember { mutableStateListOf("", "", "") }
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Security questions") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Weaker than a recovery key: anyone with the drive who can guess or find all three answers can reset the password. Spelling, capitals and punctuation don't matter.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            for (i in 0..2) {
                var open by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                ExposedDropdownMenuBox(open, { open = it }) {
                    OutlinedTextField(qs[i], { qs[i] = it }, Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable), label = { Text("Question ${i + 1}") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) })
                    ExposedDropdownMenu(open, { open = false }) { for (q in QUESTIONS) DropdownMenuItem({ Text(q) }, { qs[i] = q; open = false }) }
                }
                OutlinedTextField(ans[i], { ans[i] = it }, Modifier.fillMaxWidth(), label = { Text("Answer") }, singleLine = true)
            }
            Spacer(Modifier.height(8.dp))
            PasswordField(pw, { pw = it }, "Current password")
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }, confirmButton = { TextButton({
        busy = true
        scope.launch {
            err = withContext(Dispatchers.IO) { try { v.setQuestions(pw, qs.toList(), ans.toList()); Session.log("questions_set"); null } catch (e: Exception) { e.message } }
            busy = false; if (err == null) { Session.changed(); Session.say("Security questions saved."); onDismiss() }
        }
    }, enabled = !busy) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** How long DriveVault may stay in the background before it locks. A phone setting (not stored in the vault), so the lock screen shows it too. */
@Composable
fun AutoLockChips(modifier: Modifier = Modifier) {
    var autoMin by remember { mutableIntStateOf(Session.prefs.getInt("autolock_min", 2)) }
    Row(modifier.horizontalScroll(rememberScrollState())) {
        for (m in listOf(0, 1, 2, 5, 15)) FilterChip(autoMin == m, { autoMin = m; Session.prefs.edit().putInt("autolock_min", m).apply() },
            { Text(if (m == 0) "Immediately" else "$m min") }, Modifier.padding(horizontal = 3.dp))
    }
}
