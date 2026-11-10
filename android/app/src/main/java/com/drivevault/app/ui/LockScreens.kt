package com.drivevault.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drivevault.app.*
import com.drivevault.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen() {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(Storage.hasAllFilesAccess()) }
    var places by remember { mutableStateOf<List<Place>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf("") }
    var pathErr by remember { mutableStateOf<String?>(null) }
    OnResume { access = Storage.hasAllFilesAccess(); refresh++ }
    LaunchedEffect(access, refresh) { places = withContext(Dispatchers.IO) { Storage.places(ctx) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        try { Session.select(Storage.rememberPicked(ctx, uri)) } catch (e: Exception) { Session.say("Couldn't get access to that folder: ${e.message}") }
    }
    val pickDrive = { Session.awayOk = true; picker.launch(Storage.pickDriveIntent(ctx)) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Choose the drive") }, navigationIcon = {
            if (Session.vault != null) IconButton({ Session.screen = Screen.Lock }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { IconButton({ refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
            if (!access) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Allow access to files", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("DriveVault reads and writes the encrypted vault directly on your USB drive. Android needs \"All files access\" for that. Nothing leaves your phone.")
                        Spacer(Modifier.height(12.dp))
                        Button({
                            if (Build.VERSION.SDK_INT >= 30) try {
                                ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName)))
                            } catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        }) { Text("Allow access") }
                        TextButton(pickDrive) { Text("Or pick the drive with the system picker") }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text("Plug in the drive (USB-C or OTG adapter), then pick it. DriveVault keeps an encrypted DriveVault folder on it and leaves other files alone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val list = places
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else for (p in list) {
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { Session.select(p.drive) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (p.removable) Icons.Default.Usb else Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.drive.title, fontWeight = FontWeight.SemiBold)
                            Text((p.drive.file?.absolutePath ?: "Picked with the system picker") + (if (p.total > 0) " · ${fmtSize(p.free)} free of ${fmtSize(p.total)}" else ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (p.state) {
                            DriveState.Vault -> AssistChip({ Session.select(p.drive) }, label = { Text("Has vault") }, leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) })
                            DriveState.NotReady -> AssistChip({ refresh++ }, label = { Text("Not ready") }, leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)) })
                            DriveState.Empty -> {}
                        }
                    }
                }
            }
            if (list != null && list.none { it.removable }) Text("No USB drive found. Plug it in and tap refresh. Some phones need \"File transfer / OTG\" turned on in Settings.",
                Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (access) TextButton(pickDrive, Modifier.padding(top = 4.dp)) { Text("Drive not listed? Pick it with the system picker") }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(path, { path = it; pathErr = null }, Modifier.fillMaxWidth(), label = { Text("Or open a vault by folder path") }, singleLine = true,
                placeholder = { Text("/storage/XXXX-XXXX") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { Storage.findBase(path)?.let { Session.select(it) } ?: run { pathErr = "No DriveVault vault found there." } }),
                isError = pathErr != null, supportingText = { pathErr?.let { Text(it) } })
        }
    }
}

@Composable
fun LockScreen() {
    val ctx = LocalContext.current
    val v = Session.vault ?: run { Session.screen = Screen.Locations; return }
    // "" while the drive is being checked; "notready" when it can't be read (never offer "create" then)
    var mode by remember(v) { mutableStateOf("") }
    var retry by remember(v) { mutableIntStateOf(0) }
    LaunchedEffect(v, retry) {
        val st = withContext(Dispatchers.IO) { Session.drive?.state() ?: DriveState.NotReady }
        if (mode == "" || mode == "notready" || retry > 0) mode = when (st) { DriveState.Vault -> "unlock"; DriveState.Empty -> "create"; DriveState.NotReady -> "notready" }
    }
    var pw1 by remember { mutableStateOf("") }; var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var recKind by remember { mutableStateOf("key") }
    var rk by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "", "") }
    val info = remember(v, mode) { try { v.recoveryInfo() } catch (e: Exception) { Vault.RecoveryInfo(false, null, null, null) } }
    val myDevice = remember(v, mode) { info.device != null && info.deviceId == DeviceTrust.deviceId(ctx) && DeviceTrust.has(ctx, v.vaultId) }
    val scope = rememberCoroutineScope()
    val screenLock = rememberScreenLockCheck()

    fun run(label: String, block: () -> Unit) {
        if (busy) return
        busy = true; err = null
        scope.launch {
            err = withContext(Dispatchers.IO) {
                try { block(); null }
                catch (e: WrongPassword) { Session.failedAttempts++; Session.log(if (mode == "recover") "recover_failed" else "unlock_failed", "via" to label); kotlinx.coroutines.delay(800); e.message }
                catch (e: Exception) { Session.friendlyError(e) }
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(when (mode) { "create" -> "Protect this drive"; "recover" -> "Reset password"; "notready" -> "Drive not ready"; "" -> "Checking the drive…"; else -> "Unlock vault" },
            style = MaterialTheme.typography.headlineSmall)
        Text(when (mode) { "create" -> "An encrypted vault folder will be created on this drive."; "recover" -> "Prove it's you, then choose a new password. Files stay as they are."
            "notready" -> "DriveVault can't read this drive right now. It may still be starting up, or Android hasn't given access. Wait a few seconds and tap Retry. " +
                "If it keeps failing, tap Change and pick the drive with the system picker."
            "" -> ""
            else -> "Enter the password for the vault on this drive." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                Text(Session.drive?.label ?: "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                if (mode != "recover") TextButton({ Session.screen = Screen.Locations }) { Text("Change") }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (mode == "" ) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        if (mode == "notready") { Button({ mode = ""; retry++ }, Modifier.fillMaxWidth().height(50.dp)) { Text("Retry") }; return@Column }
        when (mode) {
            "create", "unlock" -> {
                PasswordField(pw1, { pw1 = it }, "Password", imeAction = if (mode == "create") ImeAction.Next else ImeAction.Go, onDone = {
                    if (mode == "unlock") run("password") { v.unlock(pw1); pw1 = ""; scope.launch { Session.onUnlocked("password") } }
                })
                if (mode == "create") { Spacer(Modifier.height(8.dp)); PasswordField(pw2, { pw2 = it }, "Confirm password") }
            }
            "recover" -> {
                val kinds = listOfNotNull(if (info.key) "key" else null, if (info.questions != null) "questions" else null, if (myDevice) "device" else null)
                if (recKind !in kinds && kinds.isNotEmpty()) recKind = kinds.first()
                if (kinds.size > 1) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    kinds.forEachIndexed { i, k -> SegmentedButton(recKind == k, { recKind = k }, SegmentedButtonDefaults.itemShape(i, kinds.size)) {
                        Text(when (k) { "key" -> "Key"; "questions" -> "Questions"; else -> "This phone" }) } }
                }
                Spacer(Modifier.height(8.dp))
                when (recKind) {
                    "key" -> OutlinedTextField(rk, { rk = it }, Modifier.fillMaxWidth(), label = { Text("Recovery key") }, singleLine = true, placeholder = { Text("XXXXX-XXXXX-XXXXX-XXXXX-XXXXX") })
                    "questions" -> info.questions?.forEachIndexed { i, q -> OutlinedTextField(answers[i], { answers[i] = it }, Modifier.fillMaxWidth().padding(bottom = 6.dp), label = { Text(q) }, singleLine = true) }
                    else -> Text("This phone is the trusted phone for this vault (${info.device}). You'll confirm your screen lock.", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                PasswordField(pw1, { pw1 = it }, "New password", imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                PasswordField(pw2, { pw2 = it }, "Confirm new password")
            }
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            when (mode) {
                "unlock" -> run("password") { v.unlock(pw1); pw1 = ""; scope.launch { Session.onUnlocked("password") } }
                "create" -> if (pw1 != pw2) err = "Passwords do not match." else run("create") {
                    val d = Session.drive!!
                    when (d.state()) {   // check again right before writing anything
                        DriveState.NotReady -> { scope.launch { mode = "notready" }; throw VaultException("Drive not ready / no access. Tap Retry.") }
                        DriveState.Vault -> { scope.launch { mode = "unlock" }; throw VaultException("This drive already has a vault. Enter its password.") }
                        DriveState.Empty -> {}
                    }
                    v.create(pw1)
                    try { Storage.installDesktopApp(ctx, d) } catch (_: Exception) {}
                    Session.log("created", "note" to "on ${Session.host}")
                    val key = v.setRecoveryKey(pw1); Session.log("recovery_key_created")
                    pw1 = ""; pw2 = ""
                    scope.launch { showKey = key }
                }
                "recover" -> if (pw1 != pw2) err = "New passwords do not match." else {
                    val via = when (recKind) { "key" -> "recovery key"; "questions" -> "security questions"; else -> "trusted phone" }
                    val go = {
                        run(via) {
                            when (recKind) {
                                "key" -> v.recover("key", rk, null, pw1)
                                "questions" -> v.recover("questions", null, answers.toList(), pw1)
                                else -> v.recoverWithDevice(DeviceTrust.load(ctx, v.vaultId!!), pw1)
                            }
                            Session.log("password_reset", "via" to via)
                            pw1 = ""; pw2 = ""
                            scope.launch { Session.say("Password reset. Use the new password from now on."); Session.onUnlocked(via) }
                        }
                    }
                    if (recKind == "device") { Session.awayOk = true; screenLock("Reset DriveVault password") { go() } } else go()
                }
            }
        }, Modifier.fillMaxWidth().height(50.dp), enabled = !busy) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Text(when (mode) { "create" -> "Create vault"; "recover" -> "Reset password & unlock"; else -> "Unlock" })
        }
        if (busy && mode != "create") Text("Checking… this takes a few seconds on a phone.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
        if (mode == "create") Text("There is no password reset without a recovery method. You'll get a recovery key next.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            if (mode == "unlock" && (info.key || info.questions != null || myDevice)) TextButton({ mode = "recover"; err = null; pw1 = ""; pw2 = "" }) { Text("Forgot password?") }
            if (mode == "recover") TextButton({ mode = "unlock"; err = null }) { Text("Back to password") }
            TextButton({ showLog = true }) { Text("Activity log") }
        }
        if (mode == "unlock") {
            Text("Auto-lock when DriveVault is in the background for:", Modifier.padding(top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AutoLockChips()
        }
    }
    showKey?.let { k -> RecoveryKeyDialog(k) { showKey = null; Session.onUnlocked("new vault") } }
    if (showLog) PlainLogDialog { showLog = false }
}

@Composable
fun PlainLogDialog(onDismiss: () -> Unit) {
    val lines = remember {
        try { Session.appDir()?.takeIf { it.exists("activity.log") }?.read("activity.log")?.toString(Charsets.UTF_8)?.lines()?.filter { it.isNotBlank() }
            ?.takeLast(500)?.reversed() } catch (e: Exception) { null } ?: emptyList()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Activity log (unencrypted)") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                if (lines.isEmpty()) Text("No activity yet.")
                for (l in lines) Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.padding(vertical = 2.dp))
            }
        }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
}
