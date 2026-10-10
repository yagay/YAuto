package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import kotlinx.serialization.json.*
import java.util.UUID


/** Shared MacroDroid macro, action-block and category discovery. */
internal fun macroCategoryName(obj: JsonObject): String? {
        val direct = obj.string(
            "m_categoryName", "categoryName", "m_category", "category", "m_categoryId", "categoryId"
        )?.trim()
        if (!direct.isNullOrBlank()) return direct
        val nested = sequenceOf("m_category", "category")
            .mapNotNull { obj[it] as? JsonObject }
            .mapNotNull { it.string("m_name", "name", "m_categoryName", "categoryName", "m_id", "id") }
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
        return nested
    }

internal fun findMacros(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> {
            val singleWrapped = root["macro"] as? JsonObject
            if (singleWrapped != null) listOf(singleWrapped)
            else {
                val direct = listOf("macroList", "macros", "m_macroList").firstNotNullOfOrNull { root[it] as? JsonArray }
                direct?.mapNotNull { it as? JsonObject }
                    ?: if ("m_actionList" in root || "m_triggerList" in root) listOf(root) else emptyList()
            }
        }
        else -> emptyList()
    }

internal fun findActionBlocks(root: JsonElement, macros: List<JsonObject>): List<JsonObject> {
        val blocks = linkedMapOf<String, JsonObject>()
        fun add(array: JsonArray?) {
            array?.mapNotNull { it as? JsonObject }?.forEachIndexed { index, block ->
                blocks.putIfAbsent(blockIdentifier(block, index), block)
            }
        }
        (root as? JsonObject)?.let { obj ->
            add(obj["exportedActionBlocks"] as? JsonArray)
            add(obj["actionBlocks"] as? JsonArray)
            (obj["macro"] as? JsonObject)?.let { macro ->
                add(macro["exportedActionBlocks"] as? JsonArray)
                add(macro["actionBlocks"] as? JsonArray)
            }
        }
        macros.forEach { macro ->
            add(macro["exportedActionBlocks"] as? JsonArray)
            add(macro["actionBlocks"] as? JsonArray)
        }
        return blocks.values.toList()
    }

internal fun buildActionBlockAliases(root: JsonElement, macros: List<JsonObject>): Map<String, FlowId> {
        val aliases = linkedMapOf<String, FlowId>()
        findActionBlocks(root, macros).forEachIndexed { index, block ->
            val primary = blockIdentifier(block, index)
            val flowId = FlowId("import-md-block-$primary")
            listOf("m_GUID", "guid", "id", "actionBlockId", "m_actionBlockId")
                .mapNotNull { block.primitiveText(it) }
                .plus(primary)
                .forEach { aliases[it] = flowId }
        }
        return aliases
    }

internal fun blockIdentifier(block: JsonObject, index: Int): String =
        listOf("m_GUID", "guid", "id", "actionBlockId", "m_actionBlockId")
            .firstNotNullOfOrNull { block.primitiveText(it) }
            ?: "block-$index"

