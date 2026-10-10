package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.*
import com.yagay.yauto.core.model.*
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

/** Shared strict Tasker XML argument, typed-value and escaping helpers. */
internal fun taskerJsonValue(value: String, type: String): String = when (type) {
        "java.lang.Boolean", "boolean" ->
            if (value.equals("true", true) || value == "1") "true" else "false"
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "int", "long", "short", "byte" -> value.trim().toLongOrNull()?.toString() ?: jsonString(value)
        "java.lang.Float", "java.lang.Double", "float", "double" ->
            value.trim().toDoubleOrNull()?.toString() ?: jsonString(value)
        else -> jsonString(value)
    }

internal fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

internal fun Element.argElement(index: Int): Element? =
        elementChildren().firstOrNull { it.getAttribute("sr") == "arg" + index }

internal fun Element.stringArg(index: Int): String? {
        val arg = argElement(index) ?: return null
        return when {
            arg.hasAttribute("val") -> arg.getAttribute("val")
            else -> arg.textContent?.trim()
        }?.takeIf(String::isNotEmpty)
    }

internal fun Element.intArg(index: Int): Long? {
        val arg = argElement(index) ?: return null
        return arg.getAttribute("val").takeIf(String::isNotBlank)?.toLongOrNull()
            ?: arg.textContent?.trim()?.toLongOrNull()
    }

internal fun Element.childText(name: String): String? = elementChildren().firstOrNull { it.tagName == name }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
internal fun Element.children(name: String): List<Element> = elementChildren().filter { it.tagName == name }
internal fun Element.elementChildren(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
internal fun Element.toCompactXml(): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        }.transform(DOMSource(this), StreamResult(writer))
        return writer.toString()
    }