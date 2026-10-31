                                            PrefsStore.update(this@MainActivity) { p -> p.copy(delaySec = delaySec) }
                                        } catch (_: Exception) {}
                                    }
                                },
                                valueRange = 0f..20f,
                                steps = 19,
                            )
                            Text("Set 0 to remove artificial delay before notification replies.", style = MaterialTheme.typography.bodySmall)
'''
        t = t[:insert_at] + block + t[insert_at:]

# --- 5 GGUF picker after Unload/Reload ---
if "Pick GGUF" not in t:
    old_m = '''                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        LlamaBridge.unload()
                                        modelStatus = "Unloaded"
                                    }
                                }) { Text("Unload") }
                                Button(onClick = {
                                    scope.launch {
                                        val p = PrefsStore.read(this@MainActivity)
                                        if (p.modelPath.isNotBlank()) {
                                            LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                            modelStatus = LlamaBridge.lastStatusHi
                                        } else modelStatus = "No model path"
                                    }
                                }) { Text("Reload") }
                            }
                        }
                    }
                    composable("settings")'''
    new_m = r'''                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            LlamaBridge.unload()
                                            modelStatus = "Unloaded"
                                        } catch (t: Throwable) {
                                            modelStatus = "Unload error: ${t.message}"
                                        }
                                    }
                                }) { Text("Unload") }
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            val p = PrefsStore.read(this@MainActivity)
                                            if (p.modelPath.isNotBlank()) {
                                                LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                                modelStatus = LlamaBridge.lastStatusHi
                                            } else modelStatus = "No model path"
                                        } catch (t: Throwable) {
                                            modelStatus = "Reload error: ${t.message}"
                                        }
                                    }
                                }) { Text("Reload") }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Local GGUF (storage picker)", fontWeight = FontWeight.Bold)
                            Text("Pick a .gguf from device storage, Validate, then Load / Connect.", style = MaterialTheme.typography.bodySmall)
                            var pickStatus by remember { mutableStateOf("") }
                            val pickGguf = androidx.activity.compose.rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.OpenDocument()
                            ) { uri: Uri? ->
                                if (uri == null) return@rememberLauncherForActivityResult
                                scope.launch {
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri,
                                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    } catch (_: SecurityException) {}
                                    try {
                                        val dir = File(filesDir, "models").also { it.mkdirs() }
                                        val dest = File(dir, "imported-${System.currentTimeMillis()}.gguf")
                                        contentResolver.openInputStream(uri)?.use { ins ->
                                            dest.outputStream().use { outs -> ins.copyTo(outs) }
                                        } ?: run {
                                            pickStatus = "Cannot open selected file"
                                            return@launch
                                        }
                                        val v = GgufValidator.validate(dest)
                                        if (!v.ok) {
                                            dest.delete()
                                            pickStatus = "Invalid: ${v.reason}"
                                            return@launch
                                        }
                                        PrefsStore.update(this@MainActivity) { it.copy(modelPath = dest.absolutePath, modelName = dest.name) }
                                        pickStatus = "Imported ${dest.name} (${v.sizeBytes / (1024 * 1024)} MB). Tap Load."
                                        modelStatus = pickStatus
                                    } catch (t: Throwable) {
                                        pickStatus = "Import error: ${t.message}"
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickGguf.launch(arrayOf("*/*")) }) { Text("Pick GGUF") }
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            val path = PrefsStore.read(this@MainActivity).modelPath
                                            if (path.isBlank()) {
                                                modelStatus = "No model path — Pick GGUF first"
                                                return@launch
                                            }
                                            val f = File(path)
                                            val v = GgufValidator.validate(f)
                                            modelStatus = if (v.ok) "Valid GGUF (${v.sizeBytes / (1024 * 1024)} MB)" else "Invalid: ${v.reason}"
                                        } catch (t: Throwable) {
                                            modelStatus = "Validate error: ${t.message}"
                                        }
                                    }
                                }) { Text("Validate") }
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            val p = PrefsStore.read(this@MainActivity)
                                            if (p.modelPath.isBlank()) {
                                                modelStatus = "No model path"
                                                return@launch
                                            }
                                            LlamaBridge.tryReload(p.modelPath, LlamaBridge.safeCtx(p.nCtx), LlamaBridge.safeThreads(p.threads))
                                            modelStatus = LlamaBridge.lastStatusHi
                                            ProviderPrefs.update(this@MainActivity) { it.copy(activeKind = ProviderKind.Local.id) }
                                        } catch (t: Throwable) {
                                            modelStatus = "Load error: ${t.message}"
                                        }
                                    }
                                }) { Text("Load / Use Local") }
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            LlamaBridge.unload()
                                            PrefsStore.update(this@MainActivity) { it.copy(modelPath = "", modelName = "") }
                                            modelStatus = "Cleared model path"
                                            pickStatus = ""
                                        } catch (t: Throwable) {
                                            modelStatus = "Clear error: ${t.message}"
                                        }
                                    }
                                }) { Text("Clear") }
                            }
                            if (pickStatus.isNotBlank()) Text(pickStatus, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    composable("settings")'''
    if old_m in t:
        t = t.replace(old_m, new_m, 1)
    else:
        errors.append("models Unload/Reload block not found")

# --- 6 version ---
t = t.replace(
    'Text("Version 2.1.0 · ARM64 · Local + Cloud")',
    'Text("Version 2.1.1 · ARM64 · Local + Cloud")',
)

# --- verify all ---
required = [
    ("var delaySec by remember", "delaySec state"),
    ("error loading keys", "settings crash hardening"),
    ("Cloud API / API Key", "cloud API always visible"),
    ("Pick GGUF", "GGUF picker"),
    ('Text("Validate")', "Validate"),
    ("Load / Use Local", "Load local"),
    ('Text("Clear")', "Clear"),
    ('Text("Connect")', "Connect retained"),
    ("Memory / Knowledge", "Memory in Settings"),
    ("Reply delay", "reply delay control"),
    ("Version 2.1.1", "version bump"),
    ("isDownloaded", "no duplicate download path"),
]
for needle, label in required:
    if needle not in t:
        errors.append(f"missing after patch: {label}")

if t.count("{") != t.count("}"):
    errors.append(f"brace imbalance {t.count('{')}-{t.count('}')}")

if errors:
    print("ERRORS:")
    for e in errors:
        print(" -", e)
    # Do NOT write partial file
    sys.exit(2)

p.write_text(t, encoding="utf-8")
print("OK wrote", p, "bytes", len(t))
for needle, label in required:
    print("PASS", label)
sys.exit(0)
