#!/usr/bin/env python3
"""Surgical MainActivity fixes. Exit 2 if any required change fails. Do not leave partial file."""
from pathlib import Path
import sys

p = Path("app/src/main/java/com/saraai/offline/MainActivity.kt")
t = p.read_text(encoding="utf-8")
orig = t
errors = []

def must(cond, msg):
    if not cond:
        errors.append(msg)

# --- 1 imports ---
if "import android.net.Uri" not in t:
    t = t.replace("import android.provider.Settings\n", "import android.provider.Settings\nimport android.net.Uri\n")
if "import java.io.File" not in t:
    t = t.replace("import java.util.UUID\n", "import java.util.UUID\nimport java.io.File\n")
if "import com.saraai.offline.model.GgufValidator" not in t:
    t = t.replace(
        "import com.saraai.offline.model.ModelDownloader\n",
        "import com.saraai.offline.model.ModelDownloader\nimport com.saraai.offline.model.GgufValidator\n",
    )

# --- 2 allowGroups + delaySec harden ---
old_ag = (
    "                            var allowGroups by remember { mutableStateOf(false) }\n"
    "                            LaunchedEffect(Unit) { allowGroups = PrefsStore.read(this@MainActivity).allowGroups }"
)
new_ag = (
    "                            var allowGroups by remember { mutableStateOf(false) }\n"
    "                            var delaySec by remember { mutableStateOf(4) }\n"
    "                            LaunchedEffect(Unit) {\n"
    "                                try {\n"
    "                                    val rp = PrefsStore.read(this@MainActivity)\n"
    "                                    allowGroups = rp.allowGroups\n"
    "                                    delaySec = rp.delaySec\n"
    "                                } catch (_: Exception) {}\n"
    "                            }"
)
if "var delaySec by remember" not in t:
    if old_ag in t:
        t = t.replace(old_ag, new_ag, 1)
    else:
        errors.append("allowGroups block not found")

old_le = (
    "                            LaunchedEffect(Unit) {\n"
    "                                val s = ProviderPrefs.read(this@MainActivity)\n"
    "                                activeKind = s.activeKind\n"
    "                                timeoutSec = s.timeoutSec\n"
    "                                modelField = s.modelFor(s.activeKind)\n"
    "                                baseUrlField = s.baseUrlFor(s.activeKind)\n"
    "                                keyMask = SecureApiKeys.mask(this@MainActivity, s.activeKind)\n"
    "                            }"
)
new_le = (
    "                            LaunchedEffect(Unit) {\n"
    "                                try {\n"
    "                                    val s = ProviderPrefs.read(this@MainActivity)\n"
    "                                    activeKind = s.activeKind\n"
    "                                    timeoutSec = s.timeoutSec\n"
    "                                    modelField = s.modelFor(s.activeKind)\n"
    "                                    baseUrlField = s.baseUrlFor(s.activeKind)\n"
    "                                    keyMask = SecureApiKeys.mask(this@MainActivity, s.activeKind)\n"
    "                                } catch (_: Exception) {\n"
    "                                    keyMask = \"(error loading keys)\"\n"
    "                                }\n"
    "                            }"
)
if "error loading keys" not in t:
    if old_le in t:
        t = t.replace(old_le, new_le, 1)
    else:
        errors.append("provider LaunchedEffect not found")

# --- 3 Cloud API always visible ---
if "Cloud API / API Key" not in t:
    start = "                            if (activeKind != ProviderKind.Local.id) {"
    end = (
        "                            } else {\n"
        '                                Text("Local GGUF via llama.cpp. Use Models tab to load.")\n'
        "                            }"
    )
    i0 = t.find(start)
    i1 = t.find(end, i0) if i0 >= 0 else -1
    if i0 < 0 or i1 < 0:
        errors.append(f"cloud gate markers missing i0={i0} i1={i1}")
    else:
        i1e = i1 + len(end)
        rep = r'''                            Text("Cloud API / API Key", fontWeight = FontWeight.Bold)
                            Text("Configure any cloud provider key here. Active provider is selected above.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = keyInput,
                                onValueChange = { keyInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("API key") },
                                visualTransformation = if (keyVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                    }
                                },
                            )
                            Text("Saved: $keyMask", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(modelField, { modelField = it }, Modifier.fillMaxWidth(), label = { Text("Model name") })
                            if (activeKind == ProviderKind.OpenAiCompat.id || activeKind == ProviderKind.OpenAi.id ||
                                activeKind == ProviderKind.Grok.id || activeKind == ProviderKind.NvidiaNim.id) {
                                OutlinedTextField(baseUrlField, { baseUrlField = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            if (keyInput.isNotBlank()) {
                                                SecureApiKeys.save(this@MainActivity, activeKind, keyInput.trim())
                                                keyInput = ""
                                                keyMask = SecureApiKeys.mask(this@MainActivity, activeKind)
                                            }
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
                                            connStatus = "Saved"
                                        } catch (t: Throwable) {
                                            connStatus = "Save error: ${t.message}"
                                        }
                                    }
                                }) { Text("Save") }
                                Button(enabled = !testing && activeKind != ProviderKind.Local.id, onClick = {
                                    testing = true
                                    connStatus = "Testing…"
                                    scope.launch {
                                        try {
                                            val r = AiRouter.testConnection(this@MainActivity, ProviderKind.fromId(activeKind))
                                            connStatus = if (r.ok) "OK (${r.latencyMs}ms)" else "Fail: ${r.error}"
                                        } catch (t: Throwable) {
                                            connStatus = "Fail: ${t.message}"
                                        }
                                        testing = false
                                    }
                                }) { Text("Test connection") }
                            }
                            if (connStatus.isNotBlank()) Text(connStatus)
                            Text("Timeout seconds: $timeoutSec")
                            Slider(value = timeoutSec.toFloat(), onValueChange = { timeoutSec = it.toInt() }, valueRange = 15f..90f, steps = 14)
                            if (activeKind == ProviderKind.Local.id) {
                                Text("Active: Local GGUF via llama.cpp. Use Models tab to Connect / Load.", style = MaterialTheme.typography.bodySmall)
                            }'''
        t = t[:i0] + rep + t[i1e:]
