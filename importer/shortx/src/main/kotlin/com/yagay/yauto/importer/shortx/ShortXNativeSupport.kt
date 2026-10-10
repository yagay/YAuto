package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.model.FeatureRef

internal fun shortXMediaPlaybackCommand(value: Int): String? = when (value) {
    0 -> "play"
    1 -> "pause"
    2 -> "next"
    3 -> "previous"
    4 -> "fast_forward"
    5 -> "rewind"
    6 -> "stop"
    else -> null
}

internal fun shortXMediaPlaybackNameToNumber(value: String): Int? = when (value) {
    "MediaPlaybackAction_Play" -> 0
    "MediaPlaybackAction_Pause" -> 1
    "MediaPlaybackAction_SkipToNext" -> 2
    "MediaPlaybackAction_SkipToPrevious" -> 3
    "MediaPlaybackAction_FastForward" -> 4
    "MediaPlaybackAction_Rewind" -> 5
    "MediaPlaybackAction_Stop" -> 6
    else -> null
}

/** Android stream type IDs explicitly supported by the YAuto native audio executor. */
internal fun shortXStreamFromAndroidType(type: Int): String? = when (type) {
    0 -> "voice_call"
    1 -> "system"
    2 -> "ring"
    3 -> "media"
    4 -> "alarm"
    5 -> "notification"
    else -> null
}

/** Strictly preserve an existing private slot rather than mapping a stock SystemUI slot. */
internal fun importedYAutoStatusSlot(value: String?): String? {
    val input = value ?: return null
    if (!input.startsWith("yauto_")) return null
    return input.removePrefix("yauto_").takeIf { Regex("[a-z][a-z0-9_]{0,23}").matches(it) }
}

internal fun nativeProcessPackageValid(value: String): Boolean =
    value.length in 3..180 && Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(value)

internal fun nativeAndroidDrawableName(value: String): String? =
    value.takeIf { it.startsWith("android:drawable/") }
        ?.removePrefix("android:drawable/")
        ?.takeIf { Regex("[a-z][a-z0-9_]{0,63}").matches(it) }

internal fun importedStatusIcon(value: String?): String? = when (value) {
    // Only explicit Android framework resource identities are semantically equivalent
    // to the platform drawable selected by AndroidStatusIconFeaturePack.
    "android:drawable/ic_dialog_info" -> "info"
    "android:drawable/ic_dialog_alert" -> "warning"
    "android:drawable/ic_lock_idle_lock" -> "lock"
    "android:drawable/ic_menu_upload" -> "upload"
    "android:drawable/ic_menu_save" -> "save"
    else -> null
}

internal fun nativeServiceComponent(packageName: String, className: String): String? {
    val cls = when {
        className.startsWith(".") -> className
        className.startsWith(packageName + ".") -> className
        else -> return null
    }
    val flattened = packageName + "/" + cls
    return flattened.takeIf(::importedServiceComponentValid)
}

internal fun importedServiceComponentValid(value: String): Boolean =
    Regex("""[A-Za-z_][A-Za-z0-9_.]*/[A-Za-z_.$][A-Za-z0-9_.$]*""").matches(value)

internal val SOURCE_METADATA_KEYS = setOf(
    "@type", "type", "typeUrl", "type_url", "id", "isDisabled", "note", "actionOnError",
)

internal class ProtoFields(bytes: ByteArray) {
    private val fields = Wire(bytes).fields()
    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun onlyBusinessFields(vararg allowed: Int): Boolean {
        val allowedSet = allowed.toSet()
        return fields.asSequence().filter { it.number < 96 }.all { it.number in allowedSet }
    }

    /** Strict control-flow check: unlike leaf actions, structural nodes cannot retain source
     * metadata or future fields. Only explicitly defaulted control flags (97/98 = 0) are safe.
     */
    fun onlyStructuralFields(vararg allowed: Int): Boolean {
        val accepted = allowed.toSet()
        return fields.all { field ->
            field.number in accepted ||
                ((field.number == 97 || field.number == 98) && field.wire == 0 && field.varint == 0L)
        }
    }

    fun string(number: Int): String? = bytes(number)
        ?.toString(Charsets.UTF_8)
        ?.takeIf { it.isNotEmpty() }

    fun bytes(number: Int): ByteArray? = fields
        .firstOrNull { it.number == number && it.wire == 2 }
        ?.bytes

    fun allBytes(number: Int): List<ByteArray> = fields
        .asSequence()
        .filter { it.number == number && it.wire == 2 }
        .mapNotNull { it.bytes }
        .toList()

    fun allStrings(number: Int): List<String> = allBytes(number)
        .mapNotNull { it.toString(Charsets.UTF_8).takeIf(String::isNotEmpty) }

    fun varint(number: Int): Long? = fields
        .firstOrNull { it.number == number && it.wire == 0 }
        ?.varint

    /** Repeated protobuf enum values may be unpacked or packed. */
    fun allVarints(number: Int): List<Long> = fields
        .asSequence()
        .filter { it.number == number && it.wire == 0 }
        .mapNotNull { it.varint }
        .toList()
}

internal data class ShortXChipInteractionMapping(
    val feature: FeatureRef,
    val click: List<AnyStub>,
    val longClick: List<AnyStub>,
)
