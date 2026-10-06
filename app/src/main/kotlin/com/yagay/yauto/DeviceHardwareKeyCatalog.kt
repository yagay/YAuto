package com.yagay.yauto

import android.view.KeyEvent
import com.yagay.yauto.core.registry.FieldPickerOption
import com.yagay.yauto.core.registry.HardwareKeyPickerCatalog
import com.yagay.yauto.core.registry.HardwareKeyCaptureResult
import com.yagay.yauto.platform.root.RootShell
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class DeviceHardwareKeyCatalog(
    private val rootShell: RootShell,
) {
    private val mutex = Mutex()
    @Volatile private var cached: HardwareKeyPickerCatalog? = null

    suspend fun load(force: Boolean = false): HardwareKeyPickerCatalog {
        if (!force) cached?.let { return it }
        return mutex.withLock {
            if (!force) cached?.let { return@withLock it }
            val discovered = discover()
            if (discovered.keyCodes.isNotEmpty() || discovered.scanCodes.isNotEmpty()) {
                cached = discovered
            }
            discovered
        }
    }


    suspend fun captureRawKey(timeoutMs: Long): HardwareKeyCaptureResult? {
        if (!rootShell.isAvailable()) return null
        val boundedTimeout = timeoutMs.coerceIn(1_000L, 60_000L)
        val output = rootShell.run(RAW_KEY_CAPTURE_COMMAND, boundedTimeout + 1_000L)
        val line = output.stdout.lineSequence()
            .firstOrNull { RAW_KEY_EVENT.containsMatchIn(it) }
            ?: return null
        val match = RAW_KEY_EVENT.find(line) ?: return null
        val scanCode = match.groupValues[1].toIntOrNull(16) ?: return null
        val keyCode = resolveAndroidKeyCode(scanCode)
        return HardwareKeyCaptureResult(
            keyCode = keyCode,
            scanCode = scanCode,
            deviceId = -1,
            action = KeyEvent.ACTION_DOWN,
        )
    }

    private suspend fun resolveAndroidKeyCode(scanCode: Int): Int {
        val mappings = parseKeyLayouts(rootShell.run(KEY_LAYOUT_COMMAND, 3_000L).stdout)
            .filter { it.scanCode == scanCode }
        val preferred = mappings.minByOrNull(::mappingPriority) ?: return KeyEvent.KEYCODE_UNKNOWN
        return androidKeyCodesByLabel()[preferred.label] ?: KeyEvent.KEYCODE_UNKNOWN
    }

    private suspend fun discover(): HardwareKeyPickerCatalog {
        if (!rootShell.isAvailable()) return HardwareKeyPickerCatalog()

        val layoutOutput = rootShell.run(KEY_LAYOUT_COMMAND, 8_000)
        val mappings = parseKeyLayouts(layoutOutput.stdout)
        if (mappings.isEmpty()) return HardwareKeyPickerCatalog()

        val getevent = rootShell.run("getevent -lp 2>/dev/null", 8_000)
        val devicesByScan = parseGeteventKeys(getevent.stdout)
        val activeScans = devicesByScan.keys
        val deviceMappings = if (activeScans.isNotEmpty()) {
            mappings.filter { it.scanCode in activeScans }
        } else {
            mappings
        }

        val keyCodeByLabel = androidKeyCodesByLabel()
        val preferredByScan = deviceMappings
            .groupBy { it.scanCode }
            .mapValues { (_, values) -> values.minByOrNull(::mappingPriority) ?: values.first() }

        val scanOptions = preferredByScan.values
            .map { mapping ->
                val androidCode = keyCodeByLabel[mapping.label]
                val deviceNames = devicesByScan[mapping.scanCode].orEmpty().sorted()
                val detail = buildList {
                    add("ScanCode ${mapping.scanCode}")
                    androidCode?.let { add("KeyCode $it") }
                    if (deviceNames.isNotEmpty()) add(deviceNames.joinToString(", "))
                    add(File(mapping.source).name)
                }.joinToString(", ")
                FieldPickerOption(
                    value = mapping.scanCode.toString(),
                    label = mapping.label,
                    detail = detail,
                )
            }
            .distinctBy { it.value }
            .sortedWith(compareBy<FieldPickerOption>({ it.label }, { it.value.toIntOrNull() ?: Int.MAX_VALUE }))

        val keyCodeOptions = preferredByScan.values
            .mapNotNull { mapping ->
                val keyCode = keyCodeByLabel[mapping.label] ?: return@mapNotNull null
                val deviceNames = devicesByScan[mapping.scanCode].orEmpty().sorted()
                val detail = buildList {
                    add("KeyCode $keyCode")
                    add("scan ${mapping.scanCode}")
                    if (deviceNames.isNotEmpty()) add(deviceNames.joinToString(", "))
                    add(File(mapping.source).name)
                }.joinToString(", ")
                FieldPickerOption(
                    value = keyCode.toString(),
                    label = mapping.label,
                    detail = detail,
                )
            }
            .distinctBy { it.value }
            .sortedWith(compareBy<FieldPickerOption>({ it.label }, { it.value.toIntOrNull() ?: Int.MAX_VALUE }))

        return HardwareKeyPickerCatalog(
            keyCodes = keyCodeOptions,
            scanCodes = scanOptions,
        )
    }

    private fun androidKeyCodesByLabel(): Map<String, Int> =
        KeyEvent::class.java.fields
            .asSequence()
            .filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType }
            .mapNotNull { field ->
                runCatching {
                    field.name.removePrefix("KEYCODE_") to field.getInt(null)
                }.getOrNull()
            }
            .toMap()

    private data class KeyLayoutMapping(
        val scanCode: Int,
        val label: String,
        val source: String,
    )

    private fun parseKeyLayouts(raw: String): List<KeyLayoutMapping> =
        raw.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val source = line.substring(0, separator).trim()
            val body = line.substring(separator + 1).trim()
            val parts = body.split(Regex("\\s+"))
            if (parts.size < 3 || parts[0] != "key") return@mapNotNull null
            val scanCode = parseScanCode(parts[1]) ?: return@mapNotNull null
            val label = parts[2].trim().uppercase()
            if (label.isBlank()) return@mapNotNull null
            KeyLayoutMapping(scanCode, label, source)
        }.distinctBy { Triple(it.scanCode, it.label, it.source) }.toList()

    private fun parseScanCode(raw: String): Int? {
        val value = raw.trim()
        return when {
            value.startsWith("0x", ignoreCase = true) -> value.substring(2).toIntOrNull(16)
            else -> value.toIntOrNull()
        }
    }

    private fun mappingPriority(mapping: KeyLayoutMapping): Int {
        val path = mapping.source.lowercase()
        return when {
            "/vendor/" in path || "/odm/" in path -> 0
            "/product/" in path || "/system_ext/" in path -> 1
            File(path).name.equals("generic.kl", ignoreCase = true) -> 3
            else -> 2
        }
    }

    private fun parseGeteventKeys(raw: String): Map<Int, Set<String>> {
        val output = linkedMapOf<Int, MutableSet<String>>()
        var device = ""
        var keySection = false

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("add device ") -> {
                    device = ""
                    keySection = false
                }
                trimmed.startsWith("name:") -> {
                    device = trimmed.substringAfter(':').trim().trim('"')
                }
                KEY_SECTION.matches(trimmed.substringBefore(':')) -> {
                    keySection = true
                    collectHexCodes(trimmed.substringAfter(':', ""), device, output)
                }
                keySection && EVENT_SECTION.matches(trimmed.substringBefore(':')) -> {
                    keySection = false
                }
                keySection -> collectHexCodes(trimmed, device, output)
            }
        }

        return output.mapValues { it.value.toSet() }
    }

    private fun collectHexCodes(
        text: String,
        device: String,
        output: MutableMap<Int, MutableSet<String>>,
    ) {
        HEX_CODE.findAll(text).forEach { match ->
            val scan = match.value.toIntOrNull(16) ?: return@forEach
            output.getOrPut(scan) { linkedSetOf() }.apply {
                if (device.isNotBlank()) add(device)
            }
        }
    }

    companion object {
        private val HEX_CODE = Regex("(?i)(?<![0-9a-f])[0-9a-f]{4}(?![0-9a-f])")
        private val KEY_SECTION = Regex("KEY\\s*\\(0001\\)", RegexOption.IGNORE_CASE)
        private val EVENT_SECTION = Regex("[A-Z_]+\\s*\\([0-9a-fA-F]{4}\\)")
        private val RAW_KEY_EVENT = Regex(":\\s+0001\\s+([0-9a-fA-F]{4})\\s+00000001(?:\\s*$)")
        private const val RAW_KEY_CAPTURE_COMMAND =
            "getevent -t 2>/dev/null | grep -m 1 -E ': 0001 [0-9a-fA-F]{4} 00000001
            "grep -H -E '^[[:space:]]*key[[:space:]]+' " +
                "/system/usr/keylayout/*.kl /vendor/usr/keylayout/*.kl " +
                "/product/usr/keylayout/*.kl /odm/usr/keylayout/*.kl " +
                "/system_ext/usr/keylayout/*.kl 2>/dev/null"
    }
}
"

        private const val KEY_LAYOUT_COMMAND =
            "grep -H -E '^[[:space:]]*key[[:space:]]+' " +
                "/system/usr/keylayout/*.kl /vendor/usr/keylayout/*.kl " +
                "/product/usr/keylayout/*.kl /odm/usr/keylayout/*.kl " +
                "/system_ext/usr/keylayout/*.kl 2>/dev/null"
    }
}
