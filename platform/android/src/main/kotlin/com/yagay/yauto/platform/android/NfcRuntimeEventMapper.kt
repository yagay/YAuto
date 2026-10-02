package com.yagay.yauto.platform.android

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Parcelable
import android.util.Base64
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.nio.charset.Charset
import java.util.Locale

object NfcRuntimeEventMapper {
    fun fromIntent(intent: Intent): RuntimeEvent? {
        val action = intent.action ?: return null
        if (action !in SUPPORTED_ACTIONS) return null
        val tag = parcelableTag(intent)
        val records = ndefRecords(intent)
        val uid = tag?.id?.toHexString().orEmpty()
        val technologies = tag?.techList?.toList().orEmpty()
        val textValues = records.mapNotNull(::decodeTextRecord)
        val uriValues = records.mapNotNull { record -> runCatching { record.toUri()?.toString() }.getOrNull()?.takeIf(String::isNotBlank) }
        val recordObjects = records.map(::recordObject)
        val kind = when (action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> "ndef"
            NfcAdapter.ACTION_TECH_DISCOVERED -> "tech"
            else -> "tag"
        }
        return RuntimeEvent(
            typeId = "android.event.nfc_tag",
            payload = buildMap {
                put("kind", ConfigValue.StringValue(kind))
                put("action", ConfigValue.StringValue(action))
                put("uidHex", ConfigValue.StringValue(uid))
                put("techList", ConfigValue.ListValue(technologies.map(ConfigValue::StringValue)))
                put("texts", ConfigValue.ListValue(textValues.map(ConfigValue::StringValue)))
                put("uris", ConfigValue.ListValue(uriValues.map(ConfigValue::StringValue)))
                put("records", ConfigValue.ListValue(recordObjects))
                put("recordCount", ConfigValue.NumberValue(records.size.toDouble()))
                intent.dataString?.takeIf(String::isNotBlank)?.let { put("dataUri", ConfigValue.StringValue(it)) }
            },
            source = "android.nfc.intent",
        )
    }

    private fun parcelableTag(intent: Intent): Tag? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
    }

    private fun ndefRecords(intent: Intent): List<NdefRecord> {
        val messages: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        return messages.orEmpty().mapNotNull { it as? NdefMessage }.flatMap { it.records.toList() }
    }

    private fun recordObject(record: NdefRecord): ConfigValue.ObjectValue {
        val text = decodeTextRecord(record).orEmpty()
        val uri = runCatching { record.toUri()?.toString().orEmpty() }.getOrDefault("")
        return ConfigValue.ObjectValue(
            mapOf(
                "tnf" to ConfigValue.NumberValue(record.tnf.toDouble()),
                "typeBase64" to ConfigValue.StringValue(Base64.encodeToString(record.type, Base64.NO_WRAP)),
                "idBase64" to ConfigValue.StringValue(Base64.encodeToString(record.id, Base64.NO_WRAP)),
                "payloadBase64" to ConfigValue.StringValue(Base64.encodeToString(record.payload, Base64.NO_WRAP)),
                "text" to ConfigValue.StringValue(text),
                "uri" to ConfigValue.StringValue(uri),
            )
        )
    }

    internal fun decodeTextRecord(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN || !record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload ?: return null
        if (payload.isEmpty()) return ""
        val status = payload[0].toInt() and 0xFF
        val languageLength = status and 0x3F
        val utf16 = status and 0x80 != 0
        val offset = 1 + languageLength
        if (offset > payload.size) return null
        val charset = if (utf16) Charset.forName("UTF-16") else Charsets.UTF_8
        return runCatching { String(payload, offset, payload.size - offset, charset) }.getOrNull()
    }

    private fun ByteArray.toHexString(): String = joinToString("") { byte -> String.format(Locale.ROOT, "%02X", byte.toInt() and 0xFF) }

    private val SUPPORTED_ACTIONS = setOf(
        NfcAdapter.ACTION_TAG_DISCOVERED,
        NfcAdapter.ACTION_TECH_DISCOVERED,
        NfcAdapter.ACTION_NDEF_DISCOVERED,
    )
}
