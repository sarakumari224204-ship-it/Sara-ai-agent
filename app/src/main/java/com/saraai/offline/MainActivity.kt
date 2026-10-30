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
import androidx.compose.foundation.rememberScrollState
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
import com.saraai.offline.ai.AiRouter
import com.saraai.offline.ai.ProviderKind
import com.saraai.offline.ai.ProviderPrefs
import com.saraai.offline.ai.SecureApiKeys
import com.saraai.offline.llm.LlamaBridge
import com.saraai.offline.model.ModelCatalog
import com.saraai.offline.model.ModelDownloader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    data class ChatMsg(val role: String, val text: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val provider = ProviderPrefs.read(this@MainActivity)
                if (provider.activeKind == ProviderKind.Local.id) {
                    val p = PrefsStore.read(this@MainActivity)
                    if (p.modelPath.isNotBlank() && !LlamaBridge.loaded()) {
                        LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                    }
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
        var rules by remember { mutableStateOf(listOf<InstructionEntity>()) }
        var apps by remember { mutableStateOf(MessagingAppScanner.scan(this)) }
        var notifAccess by remember { mutableStateOf(false) }
        var instText by remember { mutableStateOf("") }
        var downloadMsg by remember { mutableStateOf("") }
        var downloadPct by remember { mutableStateOf(0) }

        fun refreshDb() = scope.launch {
            val db = BureauDb.open(this@MainActivity)
            rules = db.dao().instructions()
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

        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            val nav = rememberNavController()
            val back = nav.currentBackStackEntryAsState()
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
                        ).forEach { (route, icon) ->
                            NavigationBarItem(
                                selected = back.value?.destination?.route == route,
                                onClick = { nav.navigate(route) { launchSingleTop = true } },
                                icon = { Icon(icon, contentDescription = route) },
                                label = { Text(route) },
                            )
                        }
                    }
                }
            ) { pad ->
                NavHost(nav, startDestination = "home", Modifier.padding(pad)) {
                    composable("home") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Sara AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            StatusCard("Auto Reply", if (master) "ON" else "OFF", master)
                            StatusCard("Notification access", if (notifAccess) "OK" else "Need permission", notifAccess)
                            StatusCard("Model", if (LlamaBridge.loaded()) "Loaded" else "Not loaded", LlamaBridge.loaded())
                            Button(onClick = { nav.navigate("chat") }) { Text("Chat") }
                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                Text("Notification access settings")
                            }
                        }
                    }
                    composable("chat") {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Text("Chat — AiRouter (Local + Cloud)", style = MaterialTheme.typography.titleMedium)
                            LazyColumn(Modifier.weight(1f)) {
                                items(chats) { m ->
                                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text(m.role, fontWeight = FontWeight.Bold)
                                            Text(m.text)
                                        }
                                    }
                                }
                            }
                            if (generating) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (chatError.isNotBlank()) Text(chatError, color = Color(0xFFFF5252))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(chatInput, { chatInput = it }, Modifier.weight(1f), label = { Text("Message") })
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
                                                    val p = PrefsStore.read(this@MainActivity)
                                                    val prov = ProviderPrefs.read(this@MainActivity)
                                                    val kind = ProviderKind.fromId(prov.activeKind)
                                                    if (kind is ProviderKind.Local && !LlamaBridge.loaded()) {
                                                        chatError = "Local model loaded nahi hai. Models tab se load karein, ya cloud provider select karein."
                                                        chats = chats + ChatMsg("sara", chatError)
                                                    } else {
                                                        val d = db.dao().documents().map { KnowledgeDoc(it.id, it.title, it.kind, it.body, it.category, it.enabled) }
                                                        val pr = db.dao().profiles().map {
                                                            Profile(it.id, it.name, it.gender, it.age, it.city, it.state, it.education, it.occupation, it.religion, it.caste, it.maritalStatus, it.height, it.income, it.expectations, it.family, it.contact, it.raw, it.enabled)
                                                        }
                                                        val r = db.dao().instructions().map { Instruction(it.id, it.text, it.version, it.enabled, it.priority) }
                                                        val knowledge = retrievalContext(msg, d, pr)
                                                        val prompt = buildPrompt(r, knowledge, msg, ReplyPrefs(p.language, "formal", p.length, p.emoji, p.greeting))
                                                        val result = AiRouter.generate(
                                                            this@MainActivity,
                                                            systemPrompt = "You are Sara, a helpful marriage-bureau assistant. Follow the instructions in the user message. Prefer Hindi when appropriate.",
                                                            userMessage = prompt,
                                                            maxTokens = p.maxTokens,
                                                        )
                                                        if (!result.ok) {
                                                            chatError = result.error.ifBlank { "Generation failed (${kind.label})" }
                                                            chats = chats + ChatMsg("sara", chatError)
                                                        } else {
                                                            chats = chats + ChatMsg("sara", sanitizeReply(result.text))
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
                        }
                    }
                    composable("apps") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Messaging apps", style = MaterialTheme.typography.titleMedium)
                            apps.forEach { app ->
                                val on = app.packageName in enabled
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${app.label} (${app.packageName})", Modifier.weight(1f))
                                    Switch(on, {
                                        enabled = if (it) enabled + app.packageName else enabled - app.packageName
                                        scope.launch { PrefsStore.update(this@MainActivity) { p -> p.copy(enabledPackages = enabled) } }
                                    })
                                }
                            }
                        }
                    }
                    composable("knowledge") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Knowledge / Instructions", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(instText, { instText = it }, Modifier.fillMaxWidth(), label = { Text("New instruction") })
                            Button(onClick = {
                                scope.launch {
                                    if (instText.isNotBlank()) {
                                        BureauDb.open(this@MainActivity).dao().upsertInstruction(
                                            InstructionEntity(UUID.randomUUID().toString(), instText.trim(), 1, System.currentTimeMillis())
                                        )
                                        instText = ""
                                        refreshDb()
                                    }
                                }
                            }) { Text("Add instruction") }
                            rules.forEach { r -> Text("• ${r.text}") }
                        }
                    }
                    composable("models") {
                        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                            Text("Models", style = MaterialTheme.typography.titleMedium)
                            Text(modelStatus)
                            Text("Loaded: ${LlamaBridge.loaded()}")
                            ModelCatalog.models.forEach { m ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(8.dp)) {
                                        Text(m.name, fontWeight = FontWeight.Bold)
                                        Text(m.ramNote)
                                        Button(onClick = {
                                            scope.launch {
                                                downloadMsg = "Starting…"
                                                val prog = ModelDownloader.downloadHttp(this@MainActivity, m)
                                                downloadMsg = prog.message
                                                downloadPct = prog.percent
                                                if (prog.done) {
                                                    val path = ModelDownloader.localPath(this@MainActivity, m).absolutePath
                                                    PrefsStore.update(this@MainActivity) { it.copy(modelPath = path, modelName = m.name) }
                                                    val prefs = PrefsStore.read(this@MainActivity)
                                                    val st = withContext(Dispatchers.Default) {
                                                        LlamaBridge.load(path, LlamaBridge.safeCtx(prefs.nCtx), LlamaBridge.safeThreads(prefs.threads))
                                                    }
                                                    modelStatus = LlamaBridge.lastStatusHi.ifBlank { st }
                                                } else {
                                                    downloadMsg = prog.message.ifBlank { "Download failed" }
                                                }
                                            }
                                        }) { Text("Download / Load") }
                                    }
                                }
                            }
                            if (downloadMsg.isNotBlank()) Text("$downloadPct% $downloadMsg")
                            Row {
                                Button(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.Default) { LlamaBridge.unload() }
                                        modelStatus = LlamaBridge.lastStatusHi
                                    }
                                }) { Text("Unload") }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = {
                                    scope.launch {
                                        val p = PrefsStore.read(this@MainActivity)
                                        modelStatus = "Reload…"
                                        val st = withContext(Dispatchers.Default) {
                                            LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                        }
                                        modelStatus = LlamaBridge.lastStatusHi.ifBlank { st }
                                    }
                                }) { Text("Reload saved") }
                            }
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
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("AI Provider", fontWeight = FontWeight.Bold)
                            var activeKind by remember { mutableStateOf(ProviderKind.Local.id) }
                            var timeoutSec by remember { mutableStateOf(45) }
                            var modelField by remember { mutableStateOf("") }
                            var baseUrlField by remember { mutableStateOf("") }
                            var keyInput by remember { mutableStateOf("") }
                            var keyVisible by remember { mutableStateOf(false) }
                            var keyMask by remember { mutableStateOf("(not set)") }
                            var connStatus by remember { mutableStateOf("") }
                            var testing by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                val s = ProviderPrefs.read(this@MainActivity)
                                activeKind = s.activeKind
                                timeoutSec = s.timeoutSec
                                modelField = s.modelFor(s.activeKind)
                                baseUrlField = s.baseUrlFor(s.activeKind)
                                keyMask = SecureApiKeys.mask(this@MainActivity, s.activeKind)
                            }
                            ProviderKind.all.forEach { k ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    RadioButton(selected = activeKind == k.id, onClick = {
                                        activeKind = k.id
                                        scope.launch {
                                            ProviderPrefs.update(this@MainActivity) { it.copy(activeKind = k.id) }
                                            val s = ProviderPrefs.read(this@MainActivity)
                                            modelField = s.modelFor(k.id)
                                            baseUrlField = s.baseUrlFor(k.id)
                                            keyMask = SecureApiKeys.mask(this@MainActivity, k.id)
                                            keyInput = ""
                                            if (k is ProviderKind.Local) {
                                                val p = PrefsStore.read(this@MainActivity)
                                                if (p.modelPath.isNotBlank() && !LlamaBridge.loaded()) {
                                                    LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                                    modelStatus = LlamaBridge.lastStatusHi
                                                }
                                            }
                                        }
                                    })
                                    Text(k.label, Modifier.padding(start = 4.dp))
                                }
                            }
                            Text("Active: ${ProviderKind.fromId(activeKind).label}", color = Color(0xFF00C853))
                            if (activeKind != ProviderKind.Local.id) {
                                OutlinedTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    label = { Text("API key (paste then Save)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { keyVisible = !keyVisible }) {
                                            Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                        }
                                    },
                                )
                                Text("Saved: $keyMask", style = MaterialTheme.typography.bodySmall)
                                Row {
                                    Button(onClick = {
                                        scope.launch {
                                            if (keyInput.isNotBlank()) {
                                                SecureApiKeys.save(this@MainActivity, activeKind, keyInput.trim())
                                                keyMask = SecureApiKeys.mask(this@MainActivity, activeKind)
                                                keyInput = ""
                                                keyVisible = false
                                                connStatus = "Key saved (encrypted)"
                                            }
                                        }
                                    }) { Text("Save key") }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            SecureApiKeys.delete(this@MainActivity, activeKind)
                                            keyMask = "(not set)"
                                            connStatus = "Key removed"
                                        }
                                    }) { Text("Remove key") }
                                }
                                OutlinedTextField(modelField, { modelField = it }, Modifier.fillMaxWidth(), label = { Text("Model name") }, singleLine = true)
                                if (activeKind == ProviderKind.OpenAiCompat.id || activeKind == ProviderKind.OpenAi.id ||
                                    activeKind == ProviderKind.Grok.id || activeKind == ProviderKind.NvidiaNim.id) {
                                    OutlinedTextField(baseUrlField, { baseUrlField = it }, Modifier.fillMaxWidth(), label = { Text("API base URL") }, singleLine = true)
                                }
                                Row {
                                    Button(onClick = {
                                        scope.launch {
                                            ProviderPrefs.update(this@MainActivity) { cur ->
                                                when (activeKind) {
                                                    ProviderKind.Gemini.id -> cur.copy(geminiModel = modelField.trim())
                                                    ProviderKind.OpenAi.id -> cur.copy(openaiModel = modelField.trim(), openaiBaseUrl = baseUrlField.trim().ifBlank { cur.openaiBaseUrl })
                                                    ProviderKind.Grok.id -> cur.copy(grokModel = modelField.trim(), grokBaseUrl = baseUrlField.trim().ifBlank { cur.grokBaseUrl })
                                                    ProviderKind.NvidiaNim.id -> cur.copy(nvidiaModel = modelField.trim(), nvidiaBaseUrl = baseUrlField.trim().ifBlank { cur.nvidiaBaseUrl })
                                                    ProviderKind.OpenAiCompat.id -> cur.copy(customModel = modelField.trim(), customBaseUrl = baseUrlField.trim())
                                                    else -> cur
                                                }.copy(timeoutSec = timeoutSec)
                                            }
                                            connStatus = "Settings saved"
                                        }
                                    }) { Text("Save settings") }
                                    Spacer(Modifier.width(8.dp))
                                    Button(enabled = !testing, onClick = {
                                        testing = true
                                        connStatus = "Testing…"
                                        scope.launch {
                                            val r = AiRouter.testConnection(this@MainActivity, ProviderKind.fromId(activeKind))
                                            connStatus = if (r.ok) "OK (${r.latencyMs}ms)" else "Fail: ${r.error}"
                                            testing = false
                                        }
                                    }) { Text("Test connection") }
                                }
                                if (connStatus.isNotBlank()) Text(connStatus)
                                Text("Timeout seconds: $timeoutSec")
                                Slider(value = timeoutSec.toFloat(), onValueChange = { timeoutSec = it.toInt() }, valueRange = 15f..90f, steps = 14)
                            } else {
                                Text("Local GGUF via llama.cpp. Use Models tab to load.")
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Privacy", fontWeight = FontWeight.Bold)
                            Text("Customer messages are processed temporarily. Cloud providers process requests on their servers when selected.")
                            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                            val mi = ActivityManager.MemoryInfo()
                            am.getMemoryInfo(mi)
                            Text("Diagnostics", fontWeight = FontWeight.Bold)
                            Text("Provider: ${ProviderKind.fromId(activeKind).label}")
                            Text("Native: ${LlamaBridge.available} · Model loaded: ${LlamaBridge.loaded()}")
                            AiRouter.lastResult?.let { Text("Last gen: ${it.source} · ${it.latencyMs}ms · err=${it.error.ifBlank { "-" }}") }
                            Text("Free RAM MB: ${mi.availMem / (1024 * 1024)}")
                            Text("Version 2.1.0 · ARM64 · Local + Cloud AI")
                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                Text("Notification permission")
                            }
                        }
                    }
                }
            }
        }
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
