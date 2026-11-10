package com.drivevault.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drivevault.app.Job
import com.drivevault.app.Screen
import com.drivevault.app.Session
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val Blue = Color(0xFF2F6FED)

@Composable
fun DVTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF5B8FF9))
        else -> lightColorScheme(primary = Blue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun DriveVaultApp(act: ComponentActivity) {
    DVTheme {
        val snack = remember { SnackbarHostState() }
        val t = Session.toast
        LaunchedEffect(t) { if (t != null) { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(t.first, withDismissAction = true, duration = SnackbarDuration.Long) } }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when (Session.screen) {
                    Screen.Locations -> LocationsScreen()
                    Screen.Lock -> LockScreen()
                    Screen.Browser -> BrowserScreen(act)
                    Screen.History -> HistoryScreen()
                    Screen.Security -> SecurityScreen()
                }
                // sits above the bottom bar / + button so it never blocks them
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 88.dp)) {
                    JobsPanel()
                    SnackbarHost(snack)
                }
            }
        }
    }
}

// ---------------- formatting
fun fmtSize(n: Long): String {
    if (n < 1024) return "$n B"
    var x = n.toDouble(); val u = listOf("KB", "MB", "GB", "TB"); var i = -1
    while (x >= 1024 && i < u.size - 1) { x /= 1024; i++ }
    return String.format(Locale.US, if (x < 10) "%.2f %s" else "%.1f %s", x, u[i])
}
fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))
fun fmtDur(sec0: Double): String {
    if (sec0.isNaN() || sec0.isInfinite() || sec0 < 0) return ""
    val sec = sec0.toLong()
    return when { sec < 60 -> "$sec s"; sec < 3600 -> "${sec / 60} min ${sec % 60} s"; else -> "${sec / 3600} h ${sec % 3600 / 60} min" }
}

// ---------------- progress (%, speed, time left)
@Composable
fun JobsPanel() {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Session.jobs.size) { while (Session.jobs.isNotEmpty()) { kotlinx.coroutines.delay(500); tick++ } }
    tick.let { }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (j in Session.jobs) JobCard(j)
    }
}

@Composable
private fun JobCard(j: Job) {
    val elapsed = (System.currentTimeMillis() - j.start) / 1000.0
    val pct = if (j.total > 0) (j.done.toDouble() / j.total * 100).coerceIn(0.0, 100.0) else 0.0
    val rate = if (elapsed > 0) j.done / elapsed else 0.0
    val left = if (rate > 0 && elapsed > 0.7) fmtDur((j.total - j.done) / rate) + " left" else "estimating…"
    val amount = if (j.unit == "files") "${j.done} of ${j.total} files" else "${fmtSize(j.done)} of ${fmtSize(j.total)}"
    val speed = if (j.unit == "bytes" && rate > 0 && elapsed > 0.7) " · ${fmtSize(rate.toLong())}/s" else ""
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row { Text(j.label, Modifier.weight(1f), maxLines = 1, fontWeight = FontWeight.Medium); Text(String.format(Locale.US, if (pct < 10) "%.1f%%" else "%.0f%%", pct), fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { (pct / 100).toFloat() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text("$amount$speed · ${if (pct >= 100) "finishing…" else left}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------- dialogs
@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, imeAction: ImeAction = ImeAction.Done, onDone: () -> Unit = {}) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") } })
}

/** Text prompt. onOk runs in the background; return an error message to keep the dialog open. */
@Composable
fun InputDialog(title: String, label: String, initial: String = "", ok: String = "OK", password: Boolean = false,
                onDismiss: () -> Unit, onOk: suspend (String) -> String?) {
    var v by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    fun go() { if (busy) return; busy = true; scope.launch { err = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { onOk(v) } catch (e: Exception) { e.message ?: "Error" } }; busy = false; if (err == null) onDismiss() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            Column {
                if (password) PasswordField(v, { v = it }, label, Modifier.focusRequester(fr), onDone = { go() })
                else OutlinedTextField(v, { v = it }, Modifier.fillMaxWidth().focusRequester(fr), label = { Text(label) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { go() }))
                err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton({ go() }, enabled = !busy) { Text(ok) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    LaunchedEffect(Unit) { if (Session.autoFocus) { kotlinx.coroutines.delay(150); try { fr.requestFocus() } catch (_: Exception) {} } }
}

@Composable
fun ConfirmDialog(title: String, text: String, ok: String, danger: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton({ onDismiss(); onOk() }) { Text(ok, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun RecoveryKeyDialog(key: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(onDismissRequest = {}, title = { Text("Your recovery key") },
        text = {
            Column {
                Text("If the password is ever forgotten, this key resets it. Store it away from the drive: password manager, paper, or another device.")
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                    Text(key, Modifier.fillMaxWidth().padding(14.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(8.dp))
                Text("It is shown only now.", style = MaterialTheme.typography.bodySmall)
                Row { TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(key)); Session.say("Recovery key copied. Paste it into your password manager.") }) { Text("Copy") }
                    TextButton({ ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "DriveVault recovery key: $key"), "Save recovery key")) }) { Text("Share / save") } }
            }
        },
        confirmButton = { Button(onDone) { Text("I've saved it") } })
}

/** Ask for the phone's screen lock (fingerprint / PIN). */
@Composable
fun rememberScreenLockCheck(): (title: String, onOk: () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) pending?.invoke() else Session.say("Screen lock not confirmed.")
        pending = null
    }
    return { title, onOk ->
        val km = ctx.getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION")
        val i = km.createConfirmDeviceCredentialIntent(title, "Confirm it's you")
        if (i == null) Session.say("Set a screen lock (PIN, pattern or fingerprint) on this phone first.") else { pending = onOk; launcher.launch(i) }
    }
}
