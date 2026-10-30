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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.saraai.offline.core.Instruction
import com.saraai.offline.core.KnowledgeDoc
import com.saraai.offline.core.OwnerCommand
import com.saraai.offline.core.ReplyPrefs
import com.saraai.offline.core.buildPrompt
import com.saraai.offline.core.interpretOwner
import com.saraai.offline.core.retrievalContext
import com.saraai.offline.data.BureauDb
import com.saraai.offline.data.DocumentEntity
import com.saraai.offline.data.InstructionEntity
import com.saraai.offline.data.PrefsStore
import com.saraai.offline.llm.LlamaBridge
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = uri.lastPathSegment ?: "import"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@registerForActivityResult
        val text = extract(name, bytes)
        if (text.isBlank()) return@registerForActivityResult
        val db = BureauDb.open(this)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            db.dao().upsertDocument(DocumentEntity(UUID.randomUUID().toString(), name, kindOf(name), text.take(20000), System.currentTimeMillis()))
        }
    }
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val path = copyModel(uri)
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            PrefsStore.update(this@MainActivity) { it.copy(modelPath = path) }
            val status = withContext(Dispatchers.Default) { LlamaBridge.load(path, 1024, 4) }
            android.util.Log.i("Sara", "model status length=${status.length}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { BureauScreen() }
    }

    @androidx.compose.runtime.Composable
    private fun BureauScreen() {
        val scope = rememberCoroutineScope()
        var tab by remember { mutableStateOf("home") }
        var log by remember { mutableStateOf("Model tab se LFM 2.5 ya V3B GGUF import karein. Bina model ke jawab generate nahi hoga.") }
        var input by remember { mutableStateOf("") }
        var apps by remember { mutableStateOf(listOf<Pair<String, String>>()) }
        var enabled by remember { mutableStateOf(setOf("com.whatsapp")) }
        var master by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            val p = PrefsStore.read(this@MainActivity)
            master = p.master
            enabled = p.enabledPackages
            apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to (packageManager.getApplicationLabel(it)?.toString() ?: it.packageName) }
                .sortedBy { it.second.lowercase() }
                .take(80)
        }
        MaterialTheme {
            Scaffold { pad ->
                Column(Modifier.padding(pad).padding(12.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("Sara Bureau", style = MaterialTheme.typography.headlineSmall)
                    Text("com.saraai.offline · customer chat save nahi hoti")
                    RowButtons(listOf("home", "chat", "apps", "knowledge", "model"), tab) { tab = it }
                    when (tab) {
                        "home" -> {
                            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                            val mi = ActivityManager.MemoryInfo()
                            am.getMemoryInfo(mi)
                            Text("Auto reply: $master")
                            Text("Model loaded: ${LlamaBridge.loaded()}")
                            Text("Native lib: ${LlamaBridge.available}")
                            Text("Free RAM MB: ${mi.availMem / (1024 * 1024)}")
                            Text("Notification access system screen se on karein. RemoteInput tabhi reply bhejta hai jab source app action de.")
                            Button(onClick = {
                                master = !master
                                scope.launch { PrefsStore.update(this@MainActivity) { it.copy(master = master) } }
                            }) { Text(if (master) "Auto reply OFF" else "Auto reply ON") }
                            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
                        }
                        "chat" -> {
                            Text(log)
                            TextField(input, { input = it }, label = { Text("Hindi / Hinglish / English") })
                            Button(onClick = {
                                val msg = input
                                scope.launch {
                                    val db = BureauDb.open(this@MainActivity)
                                    val cmd = interpretOwner(msg)
                                    log = when (cmd) {
                                        is OwnerCommand.Add -> {
                                            db.dao().upsertInstruction(InstructionEntity(UUID.randomUUID().toString(), cmd.text, 1, System.currentTimeMillis()))
                                            "Instruction save ho gayi."
                                        }
                                        is OwnerCommand.Delete -> {
                                            val hit = db.dao().instructions().firstOrNull { it.text.contains(cmd.query, true) }
                                            if (hit == null) "Instruction nahi mili." else {
                                                db.dao().deleteInstruction(hit.id); "Hata di."
                                            }
                                        }
                                        is OwnerCommand.NeedFile -> cmd.message
                                        is OwnerCommand.ListRules -> db.dao().instructions().joinToString("\n") { it.text }.ifBlank { "Khali" }
                                        is OwnerCommand.Pref -> {
                                            PrefsStore.update(this@MainActivity) {
                                                it.copy(
                                                    language = cmd.language ?: it.language,
                                                    emoji = cmd.emoji ?: it.emoji,
                                                    length = cmd.length ?: it.length,
                                                )
                                            }
                                            "Preference update."
                                        }
                                        OwnerCommand.None -> {
                                            if (!LlamaBridge.loaded()) "Model load nahi hai. GGUF import karein. Koi fake jawab nahi."
                                            else {
                                                val docs = db.dao().documents().map { KnowledgeDoc(it.id, it.title, it.kind, it.body) }
                                                val rules = db.dao().instructions().map { Instruction(it.id, it.text, it.version) }
                                                val p = PrefsStore.read(this@MainActivity)
                                                val prompt = buildPrompt(rules, retrievalContext(msg, docs), msg, ReplyPrefs(p.language, "formal", p.length, p.emoji, p.greeting))
                                                val answer = withContext(Dispatchers.Default) { LlamaBridge.generate(prompt, p.maxTokens) }
                                                if (answer.isBlank()) "Model ne khali output diya." else answer
                                            }
                                        }
                                    }
                                }
                            }) { Text("Bhejein") }
                        }
                        "apps" -> {
                            Text("Installed launchable apps. Sirf selected packages par auto-reply try hoga.")
                            apps.forEach { (pkg, label) ->
                                val on = pkg in enabled
                                Button(onClick = {
                                    enabled = if (on) enabled - pkg else enabled + pkg
                                    scope.launch { PrefsStore.update(this@MainActivity) { it.copy(enabledPackages = enabled) } }
                                }) { Text((if (on) "ON " else "OFF ") + label) }
                            }
                        }
                        "knowledge" -> {
                            Text("TXT CSV JSON PDF. URL auto-download nahi.")
                            Button(onClick = { importFile.launch(arrayOf("application/pdf", "text/plain", "text/*", "application/json")) }) { Text("Import document") }
                            Button(onClick = {
                                scope.launch {
                                    BureauDb.open(this@MainActivity).dao().clearDocuments()
                                    log = "Documents delete."
                                }
                            }) { Text("Documents delete") }
                        }
                        else -> {
                            Text(LlamaBridge.lastError.ifBlank { "Native llama.cpp (b3962 API) arm64." })
                            Text("Loaded: ${LlamaBridge.loaded()}")
                            Button(onClick = { importModel.launch(arrayOf("*/*")) }) { Text("Import GGUF (LFM 2.5 / V3B)") }
                            Button(onClick = { LlamaBridge.unload() }) { Text("Unload model") }
                        }
                    }
                }
            }
        }
    }

    private fun extract(name: String, bytes: ByteArray): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pdf") || bytes.take(4).toByteArray().decodeToString() == "%PDF" -> {
                PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
            }
            lower.endsWith(".csv") -> com.saraai.offline.core.parseCsv(bytes.toString(Charsets.UTF_8))
            else -> bytes.toString(Charsets.UTF_8)
        }
    }

    private fun kindOf(name: String) = when {
        name.endsWith(".pdf", true) -> "pdf"
        name.endsWith(".csv", true) -> "csv"
        name.endsWith(".json", true) -> "json"
        else -> "txt"
    }

    private fun copyModel(uri: android.net.Uri): String {
        val out = java.io.File(filesDir, "model.gguf")
        contentResolver.openInputStream(uri).use { input ->
            out.outputStream().use { input?.copyTo(it) }
        }
        return out.absolutePath
    }
}

@androidx.compose.runtime.Composable
private fun RowButtons(items: List<String>, current: String, onPick: (String) -> Unit) {
    Column {
        items.forEach { item ->
            Button(onClick = { onPick(item) }) { Text(if (item == current) "[$item]" else item) }
        }
    }
}
