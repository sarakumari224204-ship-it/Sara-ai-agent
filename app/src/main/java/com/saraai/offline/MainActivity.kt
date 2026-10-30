package com.saraai.offline

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.saraai.offline.apps.MessagingAppScanner
import com.saraai.offline.core.*
import com.saraai.offline.data.*
import com.saraai.offline.llm.LlamaBridge
import com.saraai.offline.model.ModelCatalog
import com.saraai.offline.model.ModelDownloader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    data class ChatMsg(val role: String, val text: String)

    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pendingImportUri = uri.toString()
        pendingImportName = uri.lastPathSegment ?: "import"
        importTrigger++
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pendingModelUri = uri.toString()
        modelImportTrigger++
    }

    @Volatile var pendingImportUri: String? = null
    @Volatile var pendingImportName: String = ""
    @Volatile var importTrigger: Int = 0
    @Volatile var pendingModelUri: String? = null
    @Volatile var modelImportTrigger: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = PrefsStore.read(this@MainActivity)
                if (p.modelPath.isNotBlank() && !LlamaBridge.loaded()) {
                    LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                }
            } catch (_: Exception) {}
        }
        setContent { SaraRoot() }
    }

    @Composable
    private fun SaraRoot() {
        val scope = rememberCoroutineScope()
        var dark by remember { mutableStateOf(true) }
        var master by remember { mutableStateOf(false) }
        var enabled by remember { mutableStateOf(setOf("com.whatsapp", "com.whatsapp.w4b")) }
        var modelStatus by remember { mutableStateOf(LlamaBridge.lastStatusHi) }
        var chats by remember { mutableStateOf(listOf<ChatMsg>()) }
        var chatInput by remember { mutableStateOf("") }
        var generating by remember { mutableStateOf(false) }
        var chatError by remember { mutableStateOf("") }
        var docs by remember { mutableStateOf(listOf<DocumentEntity>()) }
        var rules by remember { mutableStateOf(listOf<InstructionEntity>()) }
        var profiles by remember { mutableStateOf(listOf<ProfileEntity>()) }
        var apps by remember { mutableStateOf(MessagingAppScanner.scan(this)) }
        var notifAccess by remember { mutableStateOf(false) }
        var kTitle by remember { mutableStateOf("") }
        var kCat by remember { mutableStateOf("general") }
        var kBody by remember { mutableStateOf("") }
        var kSearch by remember { mutableStateOf("") }
        var instText by remember { mutableStateOf("") }
        var downloadId by remember { mutableStateOf(-1L) }
        var downloadPct by remember { mutableStateOf(0) }
        var downloadMsg by remember { mutableStateOf("") }
        var importPreview by remember { mutableStateOf<String?>(null) }
        var importPreviewTitle by remember { mutableStateOf("") }

        fun refreshDb() = scope.launch {
            val db = BureauDb.open(this@MainActivity)
            docs = db.dao().documents()
            rules = db.dao().instructions()
            profiles = db.dao().profiles()
        }

        LaunchedEffect(Unit) {
            val p = PrefsStore.read(this@MainActivity)
            dark = p.darkTheme
            master = p.master
            enabled = p.enabledPackages
            modelStatus = LlamaBridge.lastStatusHi.ifBlank {
                if (LlamaBridge.loaded()) "Model loaded" else "Model load nahi hai"
            }
            notifAccess = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
                ?.contains(packageName) == true
            apps = MessagingAppScanner.scan(this@MainActivity)
            refreshDb()
        }

        LaunchedEffect(importTrigger) {
            if (importTrigger == 0) return@LaunchedEffect
            val uriStr = pendingImportUri ?: return@LaunchedEffect
            val uri = android.net.Uri.parse(uriStr)
            val name = pendingImportName
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@LaunchedEffect
            val text = extract(name, bytes)
            importPreviewTitle = name
            importPreview = text.take(3000)
        }

        LaunchedEffect(modelImportTrigger) {
            if (modelImportTrigger == 0) return@LaunchedEffect
            val uriStr = pendingModelUri ?: return@LaunchedEffect
            val uri = android.net.Uri.parse(uriStr)
            val path = copyModel(uri)
            val prefs = PrefsStore.read(this@MainActivity)
            PrefsStore.update(this@MainActivity) { it.copy(modelPath = path, modelName = "Imported GGUF") }
            modelStatus = "Loading…"
            val st = withContext(Dispatchers.Default) {
                LlamaBridge.load(path, LlamaBridge.safeCtx(prefs.nCtx), LlamaBridge.safeThreads(prefs.threads))
            }
            modelStatus = LlamaBridge.lastStatusHi.ifBlank { st }
        }

        LaunchedEffect(downloadId) {
            if (downloadId < 0) return@LaunchedEffect
            while (true) {
                val prog = ModelDownloader.queryProgress(this@MainActivity, downloadId)
                if (prog == null) break
                downloadPct = prog.percent
                when {
                    prog.done -> { downloadMsg = "Download complete"; break }
                    prog.failed -> { downloadMsg = "Download fail (reason=${prog.reason})"; break }
                    else -> downloadMsg = "Downloading ${prog.percent}% (${prog.downloaded / 1024 / 1024} MB)"
                }
                delay(800)
            }
        }

        val colors = if (dark) darkColorScheme(
            primary = Color(0xFF7C4DFF),
            secondary = Color(0xFF00BFA5),
            background = Color(0xFF0F0F14),
            surface = Color(0xFF1A1A24),
        ) else lightColorScheme(primary = Color(0xFF5E35B1))

        MaterialTheme(colorScheme = colors) {
            val nav = rememberNavController()
            val back = nav.currentBackStackEntryAsState()
            val route = back.value?.destination?.route ?: "home"
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        listOf(
                            "home" to Icons.Default.Home,
                            "chat" to Icons.Default.Chat,
                            "apps" to Icons.Default.Apps,
                            "knowledge" to Icons.Default.MenuBook,
                            "models" to Icons.Default.Memory,
                            "settings" to Icons.Default.Settings,
                        ).forEach { (r, icon) ->
                            NavigationBarItem(
                                selected = route == r,
                                onClick = { nav.navigate(r) { launchSingleTop = true } },
                                icon = { Icon(icon, r) },
                                label = { Text(r.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                },
            ) { pad ->
                NavHost(nav, startDestination = "home", Modifier.padding(pad)) {
                    composable("home") {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            Text("Sara AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("Offline Marriage Bureau Agent", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            StatusCard("Model", if (LlamaBridge.loaded()) "LOADED" else "NOT LOADED", LlamaBridge.loaded())
                            StatusCard("Auto Reply", if (master) "ON" else "OFF", master)
                            StatusCard("Notification Access", if (notifAccess) "ON" else "OFF", notifAccess)
                            Spacer(Modifier.height(8.dp))
                            Text("Knowledge: ${docs.size} docs · ${profiles.size} profiles · ${rules.count { it.enabled }} instructions")
                            Text("Enabled apps: ${enabled.joinToString()}")
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { nav.navigate("chat") }) { Text("Chat") }
                                Button(onClick = { nav.navigate("knowledge") }) { Text("Knowledge") }
                                Button(onClick = { nav.navigate("models") }) { Text("Models") }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                Text("Notification access settings")
                            }
                        }
                    }
                    composable("chat") {
                        val listState = rememberLazyListState()
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Text("Chat — real local LLM (same pipeline as WhatsApp)", style = MaterialTheme.typography.titleMedium)
                            if (chatError.isNotBlank()) Text(chatError, color = MaterialTheme.colorScheme.error)
                            LazyColumn(Modifier.weight(1f), state = listState) {
                                items(chats) { m ->
                                    val bg = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                    val align = if (m.role == "user") Alignment.CenterEnd else Alignment.CenterStart
                                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = align) {
                                        Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.widthIn(max = 320.dp)) {
                                            Text(m.text, Modifier.padding(12.dp))
                                        }
                                    }
                                }
                            }
                            if (generating) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(chatInput, { chatInput = it }, Modifier.weight(1f), label = { Text("Hindi / Hinglish / English") })
                                Spacer(Modifier.width(8.dp))
                                Button(enabled = !generating && chatInput.isNotBlank(), onClick = {
                                    val msg = chatInput.trim()
                                    chatInput = ""
                                    chatError = ""
                                    chats = chats + ChatMsg("user", msg)
                                    generating = true
                                    scope.launch {
                                        try {
                                            val cmd = interpretOwner(msg)
                                            val db = BureauDb.open(this@MainActivity)
                                            when (cmd) {
                                                is OwnerCommand.Add -> {
                                                    db.dao().upsertInstruction(InstructionEntity(UUID.randomUUID().toString(), cmd.text, 1, System.currentTimeMillis()))
                                                    chats = chats + ChatMsg("sara", "Instruction save: ${cmd.text}")
                                                    refreshDb()
                                                }
                                                is OwnerCommand.Delete -> {
                                                    val hit = db.dao().instructions().firstOrNull { it.text.contains(cmd.query, true) }
                                                    if (hit != null) { db.dao().deleteInstruction(hit.id); chats = chats + ChatMsg("sara", "Hata di") }
                                                    else chats = chats + ChatMsg("sara", "Instruction nahi mili")
                                                    refreshDb()
                                                }
                                                is OwnerCommand.ListRules -> {
                                                    val t = db.dao().instructions().joinToString("\n") { "• ${it.text}" }.ifBlank { "Khali" }
                                                    chats = chats + ChatMsg("sara", t)
                                                }
                                                is OwnerCommand.Pref -> {
                                                    PrefsStore.update(this@MainActivity) {
                                                        it.copy(language = cmd.language ?: it.language, emoji = cmd.emoji ?: it.emoji, length = cmd.length ?: it.length)
                                                    }
                                                    chats = chats + ChatMsg("sara", "Preference update")
                                                }
                                                is OwnerCommand.NeedFile -> chats = chats + ChatMsg("sara", cmd.message)
                                                OwnerCommand.None -> {
                                                    if (!LlamaBridge.loaded()) {
                                                        chatError = "Model loaded नहीं है. Models tab se download/import karein."
                                                        chats = chats + ChatMsg("sara", chatError)
                                                    } else {
                                                        val p = PrefsStore.read(this@MainActivity)
                                                        val d = db.dao().documents().map { KnowledgeDoc(it.id, it.title, it.kind, it.body, it.category, it.enabled) }
                                                        val pr = db.dao().profiles().map {
                                                            Profile(it.id, it.name, it.gender, it.age, it.city, it.state, it.education, it.occupation, it.religion, it.caste, it.maritalStatus, it.height, it.income, it.expectations, it.family, it.contact, it.raw, it.enabled)
                                                        }
                                                        val r = db.dao().instructions().map { Instruction(it.id, it.text, it.version, it.enabled, it.priority) }
                                                        val knowledge = retrievalContext(msg, d, pr)
                                                        val prompt = buildPrompt(r, knowledge, msg, ReplyPrefs(p.language, "formal", p.length, p.emoji, p.greeting))
                                                        val ans = withContext(Dispatchers.Default) {
                                                            sanitizeReply(LlamaBridge.generate(prompt, p.maxTokens))
                                                        }
                                                        if (ans.isBlank()) {
                                                            chatError = "Model generation failed / empty. RAM ya model check karein."
                                                            chats = chats + ChatMsg("sara", chatError)
                                                        } else {
                                                            chats = chats + ChatMsg("sara", ans)
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            chatError = t.message ?: "error"
                                            chats = chats + ChatMsg("sara", "Error: $chatError")
                                        } finally {
                                            generating = false
                                        }
                                    }
                                }) { Text("Send") }
                            }
                            TextButton(onClick = { chats = emptyList(); chatError = "" }) { Text("Clear temporary chat") }
                        }
                    }
                    composable("apps") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Messaging apps only", style = MaterialTheme.typography.titleMedium)
                            Text("Camera / random apps nahi dikhate. Sirf notification-capable messaging.")
                            Spacer(Modifier.height(8.dp))
                            apps.forEach { app ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(app.label, fontWeight = FontWeight.Bold)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                                        Text("Installed: ${if (app.installed) "YES" else "NO"}")
                                        Text("Reply capability: ${if (app.replyLikely) "SUPPORTED (RemoteInput typical)" else "MAY BE LIMITED"}")
                                        if (!app.installed) {
                                            Text("Install nahi hai", color = MaterialTheme.colorScheme.error)
                                        } else {
                                            val on = app.packageName in enabled
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Auto Reply")
                                                Switch(on, {
                                                    enabled = if (on) enabled - app.packageName else enabled + app.packageName
                                                    scope.launch { PrefsStore.update(this@MainActivity) { it.copy(enabledPackages = enabled) } }
                                                })
                                                Text(if (on) "ON" else "OFF")
                                            }
                                        }
                                        if (!notifAccess) {
                                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                                Text("Enable Notification Access")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    composable("knowledge") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Knowledge Manager", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(kSearch, { kSearch = it }, label = { Text("Search knowledge") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("Manual entry", fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(kTitle, { kTitle = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(kCat, { kCat = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(kBody, { kBody = it }, label = { Text("Content / paste text") }, modifier = Modifier.fillMaxWidth().height(120.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (kTitle.isBlank() || kBody.isBlank()) return@Button
                                    scope.launch {
                                        BureauDb.open(this@MainActivity).dao().upsertDocument(
                                            DocumentEntity(UUID.randomUUID().toString(), kTitle, "txt", kBody.take(50000), System.currentTimeMillis(), kCat, true)
                                        )
                                        kTitle = ""; kBody = ""
                                        refreshDb()
                                    }
                                }) { Text("Save") }
                                Button(onClick = { importFile.launch(arrayOf("application/pdf", "text/plain", "text/*", "application/json", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "*/*")) }) { Text("Import file") }
                            }
                            importPreview?.let { prev ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Import preview: $importPreviewTitle", fontWeight = FontWeight.Bold)
                                        Text(prev.take(800))
                                        Row {
                                            Button(onClick = {
                                                scope.launch {
                                                    val name = importPreviewTitle
                                                    val body = prev
                                                    val db = BureauDb.open(this@MainActivity)
                                                    if (name.lowercase().endsWith(".csv")) {
                                                        val ps = parseCsvProfiles(body)
                                                        if (ps.isNotEmpty()) {
                                                            db.dao().upsertProfiles(ps.map {
                                                                ProfileEntity(it.id, it.name, it.gender, it.age, it.city, it.state, it.education, it.occupation, it.religion, it.caste, it.maritalStatus, it.height, it.income, it.expectations, it.family, it.contact, it.raw)
                                                            })
                                                        } else {
                                                            db.dao().upsertDocument(DocumentEntity(UUID.randomUUID().toString(), name, "csv", parseCsv(body).take(50000), System.currentTimeMillis(), "profiles", true))
                                                        }
                                                    } else if (name.lowercase().endsWith(".json")) {
                                                        val ps = parseJsonProfiles(body)
                                                        if (ps.isNotEmpty()) {
                                                            db.dao().upsertProfiles(ps.map {
                                                                ProfileEntity(it.id, it.name, it.gender, it.age, it.city, it.state, it.education, it.occupation, it.religion, it.caste, it.maritalStatus, it.height, it.income, it.expectations, it.family, it.contact, it.raw)
                                                            })
                                                        } else {
                                                            db.dao().upsertDocument(DocumentEntity(UUID.randomUUID().toString(), name, "json", body.take(50000), System.currentTimeMillis(), "data", true))
                                                        }
                                                    } else {
                                                        val kind = when {
                                                            name.endsWith(".pdf", true) -> "pdf"
                                                            name.endsWith(".docx", true) -> "docx"
                                                            else -> "txt"
                                                        }
                                                        db.dao().upsertDocument(DocumentEntity(UUID.randomUUID().toString(), name, kind, body.take(50000), System.currentTimeMillis(), "import", true))
                                                    }
                                                    importPreview = null
                                                    refreshDb()
                                                }
                                            }) { Text("Confirm import") }
                                            TextButton(onClick = { importPreview = null }) { Text("Cancel") }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Documents (${docs.size})", fontWeight = FontWeight.SemiBold)
                            docs.filter { kSearch.isBlank() || it.title.contains(kSearch, true) || it.body.contains(kSearch, true) }.forEach { d ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(d.title, fontWeight = FontWeight.Bold)
                                        Text("${d.kind} · ${d.category} · ${if (d.enabled) "ON" else "OFF"}")
                                        Text(d.body.take(120))
                                        Row {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    BureauDb.open(this@MainActivity).dao().upsertDocument(d.copy(enabled = !d.enabled))
                                                    refreshDb()
                                                }
                                            }) { Text(if (d.enabled) "Disable" else "Enable") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    BureauDb.open(this@MainActivity).dao().deleteDocument(d.id)
                                                    refreshDb()
                                                }
                                            }) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                            Text("Profiles (${profiles.size})", fontWeight = FontWeight.SemiBold)
                            profiles.take(20).forEach { p ->
                                Text("• ${p.name} ${p.age} ${p.city} ${p.gender}")
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Sara Instructions", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(instText, { instText = it }, label = { Text("New instruction") }, modifier = Modifier.fillMaxWidth())
                            Button(onClick = {
                                if (instText.isBlank()) return@Button
                                scope.launch {
                                    BureauDb.open(this@MainActivity).dao().upsertInstruction(
                                        InstructionEntity(UUID.randomUUID().toString(), instText.trim(), 1, System.currentTimeMillis())
                                    )
                                    instText = ""
                                    refreshDb()
                                }
                            }) { Text("Add instruction") }
                            rules.forEach { r ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(r.text)
                                        Row {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    BureauDb.open(this@MainActivity).dao().upsertInstruction(r.copy(enabled = !r.enabled))
                                                    refreshDb()
                                                }
                                            }) { Text(if (r.enabled) "Disable" else "Enable") }
                                            TextButton(onClick = {
                                                scope.launch {
                                                    BureauDb.open(this@MainActivity).dao().deleteInstruction(r.id)
                                                    refreshDb()
                                                }
                                            }) { Text("Delete") }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("What Sara Knows", style = MaterialTheme.typography.titleMedium)
                            Text("Docs: ${docs.size} · Profiles: ${profiles.size} · Active instructions: ${rules.count { it.enabled }}")
                            Text("Model: ${if (LlamaBridge.loaded()) modelStatus else "not loaded"}")
                            Text("Customer chats are NOT stored as knowledge.")
                        }
                    }
                    composable("models") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Models", style = MaterialTheme.typography.titleMedium)
                            Text("6 GB RAM device ke liye recommended model use karein.", color = MaterialTheme.colorScheme.secondary)
                            Text(modelStatus)
                            Text("Loaded: ${LlamaBridge.loaded()}")
                            if (downloadMsg.isNotBlank()) {
                                Text(downloadMsg)
                                LinearProgressIndicator(downloadPct / 100f, Modifier.fillMaxWidth())
                            }
                            ModelCatalog.models.forEach { m ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(m.name, fontWeight = FontWeight.Bold)
                                        Text("${m.quant} · ~${m.sizeMb} MB · ${m.ramNote}")
                                        Text("Hindi/Hinglish: ${if (m.hindiOk) "Yes" else "Limited"}")
                                        if (m.recommended) Text("★ Recommended for 6 GB", color = MaterialTheme.colorScheme.secondary)
                                        val local = ModelDownloader.isDownloaded(this@MainActivity, m)
                                        Text("Downloaded: ${if (local) "YES" else "NO"}")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!local) {
                                                Button(onClick = {
                                                    downloadMsg = "Starting download…"
                                                    downloadId = ModelDownloader.start(this@MainActivity, m)
                                                    if (downloadId < 0) downloadMsg = "Already present"
                                                }) { Text("Download") }
                                            } else {
                                                Button(onClick = {
                                                    scope.launch {
                                                        val path = ModelDownloader.localPath(this@MainActivity, m).absolutePath
                                                        val prefs = PrefsStore.read(this@MainActivity)
                                                        PrefsStore.update(this@MainActivity) { it.copy(modelPath = path, modelName = m.name) }
                                                        modelStatus = "Loading…"
                                                        val st = withContext(Dispatchers.Default) {
                                                            LlamaBridge.load(path, LlamaBridge.safeCtx(prefs.nCtx), LlamaBridge.safeThreads(prefs.threads))
                                                        }
                                                        modelStatus = LlamaBridge.lastStatusHi.ifBlank { st }
                                                    }
                                                }) { Text("Load") }
                                                TextButton(onClick = {
                                                    ModelDownloader.localPath(this@MainActivity, m).delete()
                                                    downloadMsg = "Deleted ${m.fileName}"
                                                }) { Text("Delete") }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("Import local GGUF") }
                            Button(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.Default) { LlamaBridge.unload() }
                                    modelStatus = LlamaBridge.lastStatusHi
                                }
                            }) { Text("Unload") }
                            Button(onClick = {
                                scope.launch {
                                    val p = PrefsStore.read(this@MainActivity)
                                    modelStatus = "Reload…"
                                    val st = withContext(Dispatchers.Default) {
                                        LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                    }
                                    modelStatus = LlamaBridge.lastStatusHi.ifBlank { st }
                                }
                            }) { Text("Reload saved model") }
                        }
                    }
                    composable("settings") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Settings", style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Dark theme"); Switch(dark, {
                                    dark = it
                                    scope.launch { PrefsStore.update(this@MainActivity) { p -> p.copy(darkTheme = it) } }
                                })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Master Auto Reply"); Switch(master, {
                                    master = it
                                    scope.launch { PrefsStore.update(this@MainActivity) { p -> p.copy(master = it) } }
                                })
                            }
                            var allowGroups by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) { allowGroups = PrefsStore.read(this@MainActivity).allowGroups }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Allow group replies"); Switch(allowGroups, {
                                    allowGroups = it
                                    scope.launch { PrefsStore.update(this@MainActivity) { p -> p.copy(allowGroups = it) } }
                                })
                            }
                            Text("Privacy", fontWeight = FontWeight.Bold)
                            Text("Customer messages are processed temporarily and are not saved as permanent knowledge. No cloud AI. No API keys.")
                            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                            val mi = ActivityManager.MemoryInfo()
                            am.getMemoryInfo(mi)
                            Text("Diagnostics", fontWeight = FontWeight.Bold)
                            Text("Native: ${LlamaBridge.available} · Model: ${LlamaBridge.loaded()}")
                            Text("Free RAM MB: ${mi.availMem / (1024 * 1024)}")
                            Text("Version 2.0.0 · ARM64 · CPU inference")
                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                Text("Notification permission")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extract(name: String, bytes: ByteArray): String {
        val lower = name.lowercase()
        return try {
            when {
                lower.endsWith(".pdf") || bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray()) -> {
                    PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
                }
                lower.endsWith(".docx") -> extractDocx(bytes)
                lower.endsWith(".csv") -> bytes.toString(Charsets.UTF_8)
                lower.endsWith(".json") -> bytes.toString(Charsets.UTF_8)
                else -> bytes.toString(Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            "Extract error: ${t.message}"
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        return try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                val sb = StringBuilder()
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val xml = zis.readBytes().toString(Charsets.UTF_8)
                        sb.append(xml.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim())
                        break
                    }
                    entry = zis.nextEntry
                }
                sb.toString().ifBlank { "DOCX text empty" }
            }
        } catch (t: Throwable) {
            "DOCX extract fail: ${t.message}"
        }
    }

    private fun copyModel(uri: android.net.Uri): String {
        val dir = File(filesDir, "models").apply { mkdirs() }
        val out = File(dir, "imported.gguf")
        contentResolver.openInputStream(uri).use { input ->
            out.outputStream().use { input?.copyTo(it) }
        }
        return out.absolutePath
    }

    @Composable
    private fun StatusCard(title: String, value: String, ok: Boolean) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title)
                Text(value, color = if (ok) Color(0xFF00C853) else Color(0xFFFF5252), fontWeight = FontWeight.Bold)
            }
        }
    }
}
