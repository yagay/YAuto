package com.yagay.yauto.platform.android

import android.content.ContentValues
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.TimeZone


internal fun AndroidPersonalDataFeaturePack.registerContactsQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.contacts.query"), FeatureKind.ACTION,
                "Query contacts", "Search Android contacts and optionally include phone numbers",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("query", "Name contains"),
                    FieldSchema.Toggle("includePhones", "Include phone numbers"),
                    FieldSchema.Number("limit", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store contact list", true),
                ),
                accessRequirements = setOf(AccessRequirement.CONTACTS),
                keywords = setOf("contacts", "people", "phonebook", "query", "search"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "contacts")) return@registerAction permissionMissing("contacts")
            val query = feature.config.string("query").resolveVariables(ctx.variables)
            val includePhones = feature.config.boolean("includePhones")
            val limit = (feature.config["limit"].numberOrNull() ?: 100.0).toInt().coerceIn(1, 500)
            val rows = runCatching {
                val output = ArrayList<ConfigValue>()
                resolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID,
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.HAS_PHONE_NUMBER,
                    ),
                    null, null, "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                    val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                    val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                    while (cursor.moveToNext() && output.size < limit) {
                        val name = cursor.getString(nameIndex).orEmpty()
                        if (query.isNotBlank() && !name.contains(query, ignoreCase = true)) continue
                        val contactId = cursor.getLong(idIndex)
                        val phones = if (includePhones && cursor.getInt(phoneIndex) != 0) contactPhones(contactId) else emptyList()
                        output += ConfigValue.ObjectValue(
                            mapOf(
                                "id" to ConfigValue.NumberValue(contactId.toDouble()),
                                "name" to ConfigValue.StringValue(name),
                                "lookupKey" to ConfigValue.StringValue(cursor.getString(lookupIndex).orEmpty()),
                                "phones" to ConfigValue.ListValue(phones.map(ConfigValue::StringValue)),
                            )
                        )
                    }
                }
                ConfigValue.ListValue(output)
            }.getOrElse { return@registerAction failure(it) }
            store(feature.config.string("resultVariable"), rows, ctx)
            ActionExecutionResult(true, rows)
        }
    }

internal fun AndroidPersonalDataFeaturePack.contactPhones(contactId: Long): List<String> {
        val result = ArrayList<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) cursor.getString(numberIndex)?.takeIf(String::isNotBlank)?.let(result::add)
        }
        return result.distinct()
    }

