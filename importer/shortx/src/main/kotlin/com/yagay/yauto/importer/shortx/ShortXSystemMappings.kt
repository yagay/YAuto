package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.sourceFeature
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import java.util.Base64
import kotlinx.serialization.json.*


/** Verified ShortX chip, process and service conversions, shared by binary and JSON paths. */
internal fun ShortXMappings.nativeChipInteraction(any: AnyStub, importerId: String, chipId: String): ShortXChipInteractionMapping? {
        if (!Regex("[a-z][a-z0-9_]{0,23}").matches(chipId)) return null
        if (shortName(any.typeUrl) != "ShowStatusBarChip") return null
        if (any.isJson) {
            val obj = runCatching { Json.parseToJsonElement(any.value.toString(Charsets.UTF_8)) as? JsonObject }
                .getOrNull() ?: return null
            if (!jsonBusinessKeysSafe(obj, setOf("text", "icon", "clickAction", "longClickAction")) ||
                obj["customContextDataKey"] != null && obj["customContextDataKey"] !is JsonNull) return null
            val title = (obj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 48 }
                ?: return null
            val icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val res = nativeAndroidDrawableName(icon)
            if (icon.isNotBlank() && res == null) return null
            fun parsed(key: String): List<AnyStub>? {
                val data = obj[key] as? JsonArray ?: return if (obj[key] == null) emptyList() else null
                if (data.size > 24) return null
                return data.mapIndexed { index, element ->
                    val value = element as? JsonObject ?: return null
                    val type = sequenceOf("@type", "typeUrl", "type_url", "type")
                        .mapNotNull { (value[it] as? JsonPrimitive)?.contentOrNull }
                        .firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
                    AnyStub(type, value.toString().encodeToByteArray(), isJson = true)
                }
            }
            val click = parsed("clickAction") ?: return null
            val long = parsed("longClickAction") ?: return null
            if (click.size + long.size > 24) return null
            return ShortXChipInteractionMapping(
                sourceFeature("android.status_chip.control", importerId, any.typeUrl,
                    any.value.toString(Charsets.UTF_8), extra = chipDisplayConfig(chipId, title, res)),
                click, long,
            )
        }
        val data = runCatching { ProtoFields(any.value) }.getOrNull() ?: return null
        if (!data.onlyBusinessFields(1, 2, 3, 4) || data.has(96)) return null
        val title = data.string(1)?.takeIf { it.isNotBlank() && it.length <= 48 } ?: return null
        val icon = data.string(2).orEmpty()
        val res = nativeAndroidDrawableName(icon)
        if (icon.isNotBlank() && res == null) return null
        fun parsed(field: Int): List<AnyStub>? {
            val chunks = data.allBytes(field)
            if (chunks.size > 24) return null
            return chunks.map { bytes ->
                val nested = runCatching { ProtoFields(bytes) }.getOrNull() ?: return null
                if (!nested.onlyBusinessFields(1, 2)) return null
                val type = nested.string(1)?.takeIf { it.isNotBlank() } ?: return null
                val raw = nested.bytes(2) ?: return null
                AnyStub(type, raw)
            }
        }
        val click = parsed(3) ?: return null
        val long = parsed(4) ?: return null
        if (click.size + long.size > 24) return null
        return ShortXChipInteractionMapping(
            binaryFeature(any, importerId, "android.status_chip.control", chipDisplayConfig(chipId, title, res)),
            click, long,
        )
    }

internal fun ShortXMappings.chipDisplayConfig(chipId: String, title: String, drawable: String?) = mapOf(
        "mode" to ConfigValue.StringValue("show"),
        "chipId" to ConfigValue.StringValue(chipId),
        "text" to ConfigValue.StringValue(title),
        "iconMode" to ConfigValue.StringValue(if (drawable == null) "none" else "android_drawable"),
        "icon" to ConfigValue.StringValue(drawable.orEmpty()),
    )

    /** ShortX AppPkg repeated #1 and package-set references #2. No UI launch. */
internal fun ShortXMappings.nativeStartAppProcess(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2)) return null
        val values = fields.allBytes(1).map { raw ->
            val nested = runCatching { ProtoFields(raw) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1, 2)) return null
            val pkg = nested.string(1)?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (nested.varint(2) ?: 0L).takeIf { it in 0L..99L } ?: return null
            pkg to user
        }
        val users = values.map { it.second }.distinct()
        if (users.size > 1) return null // One YAuto action has one user ID.
        val names = values.map { it.first }.distinct()
        val packageSets = fields.allStrings(2).distinct()
        if (packageSets.any { !Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,95}").matches(it) }) return null
        if ((names.isEmpty() && packageSets.isEmpty()) || names.size + packageSets.size > 24) return null
        return binaryFeature(any, importerId, "android.app.process.start", mapOf(
            "packages" to ConfigValue.StringValue(names.joinToString("\n")),
            "packageSets" to ConfigValue.StringValue(packageSets.joinToString("\n")),
            "userId" to ConfigValue.NumberValue((users.singleOrNull() ?: 0L).toDouble()),
        ))
    }

    /** ShortX pkgAndUsers repeated StringPair; different user IDs stay lossless source nodes. */
internal fun ShortXMappings.nativeStartAppProcessByPkg(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val targets = fields.allBytes(1).map { raw ->
            val nested = runCatching { ProtoFields(raw) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1, 2)) return null
            val pkg = nested.string(1)?.takeIf(::nativeProcessPackageValid) ?: return null
            val uid = nested.string(2)?.toLongOrNull()?.takeIf { it in 0L..99L } ?: return null
            pkg to uid
        }
        if (targets.isEmpty() || targets.size > 24 || targets.map { it.second }.distinct().size != 1) return null
        return binaryFeature(any, importerId, "android.app.process.start", mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
        ))
    }

    /** Preserve embedded click/long-click Any action chains until their execution is identical. */
internal fun ShortXMappings.nativeShowStatusChip(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1, 2, 3, 4) || fields.has(3) || fields.has(4)) return null
        val text = fields.string(1)?.takeIf { it.isNotBlank() && it.length <= 48 } ?: return null
        val icon = fields.string(2).orEmpty()
        val frameworkName = nativeAndroidDrawableName(icon)
        if (icon.isNotBlank() && frameworkName == null) return null
        return binaryFeature(any, importerId, "android.status_chip.control", mapOf(
            "mode" to ConfigValue.StringValue("show"),
            "chipId" to ConfigValue.StringValue("shortx"),
            "text" to ConfigValue.StringValue(text),
            "iconMode" to ConfigValue.StringValue(if (frameworkName == null) "none" else "android_drawable"),
            "icon" to ConfigValue.StringValue(frameworkName.orEmpty()),
        ))
    }

internal fun ShortXMappings.nativeJsonStartAppProcess(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("appPkg", "pkgSets"))) return null
        val targets = (obj["appPkg"] as? JsonArray)?.map { rawTarget ->
            val target = rawTarget as? JsonObject ?: return null
            if (target.keys.any { it !in setOf("pkgName", "userId") }) return null
            val pkg = (target["pkgName"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (target["userId"] as? JsonPrimitive)?.longOrNull ?: 0L
            if (user !in 0L..99L) return null
            pkg to user
        }.orEmpty()
        val sets = (obj["pkgSets"] as? JsonArray)?.map { item ->
            (item as? JsonPrimitive)?.contentOrNull?.takeIf {
                Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,95}").matches(it)
            } ?: return null
        }.orEmpty()
        if (targets.map { it.second }.distinct().size > 1 ||
            (targets.isEmpty() && sets.isEmpty()) || targets.size + sets.size > 24) return null
        return sourceFeature("android.app.process.start", importerId, any.typeUrl, raw, extra = mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "packageSets" to ConfigValue.StringValue(sets.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue((targets.firstOrNull()?.second ?: 0L).toDouble()),
        ))
    }

internal fun ShortXMappings.nativeJsonStartAppProcessByPkg(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("pkgAndUsers"))) return null
        val targets = (obj["pkgAndUsers"] as? JsonArray)?.map { item ->
            val pair = item as? JsonObject ?: return null
            if (pair.keys.any { it !in setOf("first", "second") }) return null
            val pkg = (pair["first"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf(::nativeProcessPackageValid) ?: return null
            val user = (pair["second"] as? JsonPrimitive)?.contentOrNull
                ?.toLongOrNull()?.takeIf { it in 0L..99L } ?: return null
            pkg to user
        } ?: return null
        if (targets.isEmpty() || targets.size > 24 || targets.map { it.second }.distinct().size != 1) return null
        return sourceFeature("android.app.process.start", importerId, any.typeUrl, raw, extra = mapOf(
            "packages" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
            "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
        ))
    }

internal fun ShortXMappings.nativeJsonShowStatusChip(obj: JsonObject, any: AnyStub, importerId: String, raw: String): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("text", "icon", "clickAction", "longClickAction")) ||
            (obj["clickAction"] as? JsonArray)?.isNotEmpty() == true ||
            (obj["longClickAction"] as? JsonArray)?.isNotEmpty() == true) return null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= 48 }
            ?: return null
        val icon = (obj["icon"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val frameworkName = nativeAndroidDrawableName(icon)
        if (icon.isNotEmpty() && frameworkName == null) return null
        return sourceFeature("android.status_chip.control", importerId, any.typeUrl, raw, extra = mapOf(
            "mode" to ConfigValue.StringValue("show"),
            "chipId" to ConfigValue.StringValue("shortx"),
            "text" to ConfigValue.StringValue(text),
            "iconMode" to ConfigValue.StringValue(if (frameworkName == null) "none" else "android_drawable"),
            "icon" to ConfigValue.StringValue(frameworkName.orEmpty()),
        ))
    }

internal fun ShortXMappings.nativeStatusBarIcon(
        any: AnyStub, importerId: String, fields: ProtoFields, mode: String,
    ): FeatureRef? {
        if (!fields.onlyBusinessFields(*(if (mode == "show") intArrayOf(1, 2) else intArrayOf(1)))) return null
        val slot = importedYAutoStatusSlot(fields.string(1)) ?: return null
        val input = fields.string(2)
        val icon = if (mode == "show") importedStatusIcon(input) else "info"
        val drawable = if (mode == "show" && icon == null) nativeAndroidDrawableName(input.orEmpty()) else null
        if (mode == "show" && icon == null && drawable == null) return null
        return binaryFeature(any, importerId, "android.status_icon.control", mapOf(
            "mode" to ConfigValue.StringValue(mode),
            "slot" to ConfigValue.StringValue(slot),
            "icon" to ConfigValue.StringValue(icon ?: "info"),
            "iconSource" to ConfigValue.StringValue(if (drawable == null) "built_in" else "android_drawable"),
            "drawable" to ConfigValue.StringValue(drawable.orEmpty()),
        ))
    }

internal fun ShortXMappings.nativeJsonStatusBarIcon(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String, mode: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, if (mode == "show") setOf("slot", "icon") else setOf("slot"))) return null
        val slot = importedYAutoStatusSlot((obj["slot"] as? JsonPrimitive)?.contentOrNull) ?: return null
        val input = (obj["icon"] as? JsonPrimitive)?.contentOrNull
        val icon = if (mode == "show") importedStatusIcon(input) else "info"
        val drawable = if (mode == "show" && icon == null) nativeAndroidDrawableName(input.orEmpty()) else null
        if (mode == "show" && icon == null && drawable == null) return null
        return sourceFeature("android.status_icon.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue(mode),
                "slot" to ConfigValue.StringValue(slot),
                "icon" to ConfigValue.StringValue(icon ?: "info"),
                "iconSource" to ConfigValue.StringValue(if (drawable == null) "built_in" else "android_drawable"),
                "drawable" to ConfigValue.StringValue(drawable.orEmpty()),
            ),
        )
    }

    /**
     * Support only an unambiguous flattened service component. Other AppComponent layouts,
     * selectors and unknown business fields remain raw compatibility nodes.
     */
internal fun ShortXMappings.nativeStopServices(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val components = fields.allBytes(1).map { bytes ->
            val nested = runCatching { ProtoFields(bytes) }.getOrNull() ?: return null
            if (!nested.onlyBusinessFields(1)) return null
            nested.string(1)?.takeIf(::importedServiceComponentValid) ?: return null
        }.distinct()
        if (components.isEmpty() || components.size > 32) return null
        return binaryFeature(any, importerId, "android.service.control",
            mapOf(
                "mode" to ConfigValue.StringValue("stop"),
                "components" to ConfigValue.StringValue(components.joinToString("\n")),
            ),
        )
    }

    /**
     * ShortX's documented StopService JSON uses AppComponent {
     *   pkg: {pkgName, userId}, className
     * }. We only flatten explicit components whose user IDs agree.
     * Legacy flattened test fixtures are still accepted.
     */
internal fun ShortXMappings.nativeJsonStopServices(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("services"))) return null
        val items = obj["services"] as? JsonArray ?: return null
        if (items.isEmpty() || items.size > 32) return null
        val targets = items.map { item ->
            val nested = item as? JsonObject ?: return null
            if (nested.keys == setOf("component")) {
                val component = (nested["component"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(::importedServiceComponentValid) ?: return null
                component to 0L
            } else {
                if (nested.keys != setOf("pkg", "className")) return null
                val pkg = nested["pkg"] as? JsonObject ?: return null
                if (!pkg.keys.all { it in setOf("pkgName", "userId") }) return null
                val packageName = (pkg["pkgName"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(::nativeProcessPackageValid) ?: return null
                val parsedUser = (pkg["userId"] as? JsonPrimitive)?.longOrNull
                if ("userId" in pkg && parsedUser == null) return null
                val userId = parsedUser ?: 0L
                if (userId !in 0L..999L) return null
                val className = (nested["className"] as? JsonPrimitive)?.contentOrNull ?: return null
                val component = nativeServiceComponent(packageName, className) ?: return null
                component to userId
            }
        }
        if (targets.map { it.second }.distinct().size != 1) return null
        return sourceFeature("android.service.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue("stop"),
                "components" to ConfigValue.StringValue(targets.map { it.first }.distinct().joinToString("\n")),
                "userId" to ConfigValue.NumberValue(targets.first().second.toDouble()),
            ),
        )
    }

    /** Direct service Intent subset: reject implicit targets, unknown keys and unsupported extras. */
internal fun ShortXMappings.nativeJsonGetScreenOnTime(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("from"))) return null
        val fromValue = (obj["from"] as? JsonPrimitive)?.intOrNull
        if ("from" in obj && fromValue == null) return null
        val from = when (fromValue ?: 0) {
            0 -> "last_screen_off"
            1 -> "system_ready"
            else -> return null
        }
        return sourceFeature("android.screen_on_time.get", importerId, any.typeUrl, raw,
            extra = mapOf(
                "from" to ConfigValue.StringValue(from),
                "resultVariable" to ConfigValue.StringValue("screenOnTime"),
            ))
    }

internal fun ShortXMappings.nativeGetScreenOnTime(any: AnyStub, importerId: String, fields: ProtoFields): FeatureRef? {
        if (!fields.onlyBusinessFields(1)) return null
        val from = when (fields.varint(1) ?: 0L) {
            0L -> "last_screen_off"
            1L -> "system_ready"
            else -> return null
        }
        return binaryFeature(any, importerId, "android.screen_on_time.get",
            mapOf(
                "from" to ConfigValue.StringValue(from),
                "resultVariable" to ConfigValue.StringValue("screenOnTime"),
            ))
    }

internal fun ShortXMappings.nativeJsonStartService(
        obj: JsonObject, any: AnyStub, importerId: String, raw: String,
    ): FeatureRef? {
        if (!jsonBusinessKeysSafe(obj, setOf("intent", "userId", "isForegroundService"))) return null
        val intent = obj["intent"] as? JsonObject ?: return null
        if (!intent.keys.all { it in setOf("pkgName", "className", "action", "data", "flags", "extras") }) return null
        val packageName = (intent["pkgName"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(::nativeProcessPackageValid) ?: return null
        val className = (intent["className"] as? JsonPrimitive)?.contentOrNull ?: return null
        val component = nativeServiceComponent(packageName, className) ?: return null
        val parsedUser = (obj["userId"] as? JsonPrimitive)?.longOrNull
        if ("userId" in obj && parsedUser == null) return null
        val userId = parsedUser ?: 0L
        if (userId !in 0L..999L) return null
        val parsedForeground = (obj["isForegroundService"] as? JsonPrimitive)?.booleanOrNull
        if ("isForegroundService" in obj && parsedForeground == null) return null
        val foreground = parsedForeground ?: false
        val action = (intent["action"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val dataUri = (intent["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (action.isNotBlank() && !Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(action)) return null
        if (dataUri.isNotBlank() && (dataUri.length > 2_048 ||
                !Regex("[A-Za-z][A-Za-z0-9+.-]*:.*").matches(dataUri))) return null
        val parsedFlags = (intent["flags"] as? JsonPrimitive)?.longOrNull
        if ("flags" in intent && parsedFlags == null) return null
        val flags = parsedFlags ?: 0L
        if (flags !in 0L..4294967295L) return null
        val extras = intent["extras"] as? JsonArray
        if (intent["extras"] != null && extras == null) return null
        if (extras != null && (extras.size > 24 || !extras.all(::shortXServiceExtraSafe) ||
                extras.map { ((it as JsonObject)["key"] as JsonPrimitive).content }.distinct().size != extras.size)) return null
        val extrasJson = extras?.toString().orEmpty()
        return sourceFeature("android.service.control", importerId, any.typeUrl, raw,
            extra = mapOf(
                "mode" to ConfigValue.StringValue(if (foreground) "start_foreground" else "start"),
                "component" to ConfigValue.StringValue(component),
                "userId" to ConfigValue.NumberValue(userId.toDouble()),
                "intentAction" to ConfigValue.StringValue(action),
                "dataUri" to ConfigValue.StringValue(dataUri),
                "intentFlags" to ConfigValue.NumberValue(flags.toDouble()),
                "intentExtrasJson" to ConfigValue.StringValue(extrasJson),
            ),
        )
    }

internal fun ShortXMappings.shortXServiceExtraSafe(item: JsonElement): Boolean {
        val obj = item as? JsonObject ?: return false
        if (obj.keys != setOf("key", "type", "value")) return false
        val key = (obj["key"] as? JsonPrimitive)?.contentOrNull ?: return false
        val type = (obj["type"] as? JsonPrimitive)?.intOrNull ?: return false
        val value = (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return false
        if (!Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}").matches(key) || value.length > 4096 ||
            value.contains('\n') || value.contains('\r') || value.contains('\u0000')) return false
        return when (type) {
            0 -> value.toIntOrNull() != null
            1 -> value.toLongOrNull() != null
            2 -> true
            3 -> value == "true" || value == "false"
            4 -> value.toFloatOrNull()?.isFinite() == true
            5 -> value.toDoubleOrNull()?.isFinite() == true
            else -> false
        }
    }

internal fun ShortXMappings.jsonBusinessKeysSafe(obj: JsonObject, keys: Set<String>): Boolean =
        obj.keys.all { key -> key in keys || key in SOURCE_METADATA_KEYS }

