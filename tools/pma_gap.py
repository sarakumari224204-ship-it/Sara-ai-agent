

# --- 4 Memory / Instructions / delay before Privacy ---
if "Memory / Knowledge" not in t:
    pin = t.find('Text("Privacy", fontWeight = FontWeight.Bold)')
    if pin < 0:
        errors.append("Privacy heading missing")
    else:
        hd = t.rfind("HorizontalDivider", 0, pin)
        insert_at = t.rfind("\n", 0, hd if hd > 0 else pin) + 1
        block = '''                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Text("Memory / Knowledge", fontWeight = FontWeight.Bold)
                            Text("Explicit knowledge only — customer WhatsApp messages are NOT auto-saved. Manage entries in the Knowledge tab.", style = MaterialTheme.typography.bodySmall)
                            Text("Instructions", fontWeight = FontWeight.Bold)
                            Text("Owner instructions are managed in the Knowledge tab and applied to chat + auto-reply.", style = MaterialTheme.typography.bodySmall)
                            Text("Reply delay (seconds): $delaySec")
                            Slider(
                                value = delaySec.toFloat(),
                                onValueChange = {
                                    delaySec = it.toInt()
                                    scope.launch {
                                        try {
