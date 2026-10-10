package com.yagay.yauto.importer.shortx

/** Version-tolerant decoding of verified ShortX wire payloads. */
internal object ShortXProtoReader {
    fun read(bytes: ByteArray): ShortXContent {
        val rules = readRules(bytes)
        val functions = readFunctions(bytes)
        val directActions = readDirectActions(bytes)
        return ShortXContent(
            rules = rules.distinctBy { it.id },
            functions = functions.distinctBy { it.id },
            directActions = directActions.distinctBy { it.id },
        )
    }

    fun readRules(bytes: ByteArray): List<RuleStub> {
        val top = Wire(bytes).fields()
        val directList = decodeRuleList(bytes)
        if (directList.isNotEmpty()) return directList

        val fromRuleSets = top
            .filter { it.number == 1 && it.wire == 2 }
            .flatMap { setField ->
                val setFields = Wire(setField.bytes ?: byteArrayOf()).fields()
                setFields
                    .filter { it.number == 7 && it.wire == 2 }
                    .flatMap { decodeRuleList(it.bytes ?: byteArrayOf()) }
            }
        if (fromRuleSets.isNotEmpty()) return fromRuleSets

        return listOfNotNull(decodeRule(bytes))
    }

    fun readFunctions(bytes: ByteArray): List<FunctionStub> {
        val list = decodeFunctionList(bytes)
        if (list.isNotEmpty()) return list
        return listOfNotNull(decodeFunction(bytes))
    }

    fun readDirectActions(bytes: ByteArray): List<DirectActionStub> {
        val directList = decodeDirectActionList(bytes)
        if (directList.isNotEmpty()) return directList

        val top = Wire(bytes).fields()
        val fromSets = top
            .filter { it.number == 1 && it.wire == 2 }
            .flatMap { setField ->
                val setFields = Wire(setField.bytes ?: byteArrayOf()).fields()
                setFields
                    .filter { it.number == 7 && it.wire == 2 }
                    .flatMap { decodeDirectActionList(it.bytes ?: byteArrayOf()) }
            }
        if (fromSets.isNotEmpty()) return fromSets

        return listOfNotNull(decodeDirectAction(bytes))
    }

    private fun decodeRuleList(bytes: ByteArray): List<RuleStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeRule(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

    private fun decodeRule(bytes: ByteArray): RuleStub? = runCatching {
        val fields = Wire(bytes).fields()
        fun text(number: Int) = textField(fields, number)
        fun bool(number: Int, default: Boolean) =
            fields.firstOrNull { it.number == number && it.wire == 0 }?.varint?.let { it != 0L } ?: default
        fun anys(number: Int) =
            fields.filter { it.number == number && it.wire == 2 }
                .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) }

        val id = text(4)
        val title = text(9)
        if (!looksLikeId(id) || !looksLikeText(title)) return@runCatching null
        RuleStub(
            id = id,
            title = title,
            description = text(10).ifBlank { null },
            enabled = bool(11, true),
            facts = anys(1),
            conditions = anys(2),
            actions = anys(3),
        )
    }.getOrNull()

    private fun decodeFunctionList(bytes: ByteArray): List<FunctionStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeFunction(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }

    private fun decodeFunction(bytes: ByteArray): FunctionStub? = runCatching {
        val fields = Wire(bytes).fields()
        val id = textField(fields, 1)
        val name = textField(fields, 2)
        if (!looksLikeId(id) || !looksLikeText(name)) return@runCatching null
        val parameters = fields
            .filter { it.number == 4 && it.wire == 2 }
            .mapNotNull { decodeParameter(it.bytes ?: byteArrayOf()) }
        val actions = fields
            .filter { it.number == 5 && it.wire == 2 }
            .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) }
        FunctionStub(
            id = id,
            name = name,
            returnType = textField(fields, 3),
            parameters = parameters,
            actions = actions,
            comments = textField(fields, 8).ifBlank { null },
        )
    }.getOrNull()

    private fun decodeDirectActionList(bytes: ByteArray): List<DirectActionStub> =
        Wire(bytes).fields()
            .filter { it.number == 1 && it.wire == 2 }
            .mapNotNull { decodeDirectAction(it.bytes ?: byteArrayOf()) }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

    private fun decodeDirectAction(bytes: ByteArray): DirectActionStub? = runCatching {
        val fields = Wire(bytes).fields()
        val id = textField(fields, 2)
        val title = textField(fields, 6)
        if (!looksLikeId(id) || !looksLikeText(title)) return@runCatching null
        DirectActionStub(
            id = id,
            title = title,
            description = textField(fields, 7).ifBlank { null },
            parameters = fields
                .filter { it.number == 12 && it.wire == 2 }
                .mapNotNull { decodeParameter(it.bytes ?: byteArrayOf()) },
            actions = fields
                .filter { it.number == 1 && it.wire == 2 }
                .mapNotNull { decodeAnyOrNull(it.bytes ?: byteArrayOf()) },
        )
    }.getOrNull()

    private fun decodeParameter(bytes: ByteArray): FuncParameterStub? = runCatching {
        val fields = Wire(bytes).fields()
        val name = textField(fields, 1)
        if (name.isBlank()) return@runCatching null
        FuncParameterStub(
            name = name,
            defaultValue = textField(fields, 2),
            required = fields.firstOrNull { it.number == 3 && it.wire == 0 }?.varint == 1L,
            comments = textField(fields, 4),
        )
    }.getOrNull()

    private fun decodeAnyOrNull(bytes: ByteArray): AnyStub? = runCatching {
        val fields = Wire(bytes).fields()
        val type = textField(fields, 1)
        val value = fields.firstOrNull { it.number == 2 && it.wire == 2 }?.bytes
            ?: return@runCatching null
        if (!looksLikeText(type)) return@runCatching null
        AnyStub(type, value)
    }.getOrNull()

    private fun textField(fields: List<Wire.Field>, number: Int): String =
        fields.firstOrNull { it.number == number && it.wire == 2 }
            ?.bytes
            ?.toString(Charsets.UTF_8)
            .orEmpty()

    private fun looksLikeId(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value.all { it.code in 32..126 }

    private fun looksLikeText(value: String): Boolean =
        value.isNotBlank() && value.length <= 4096 && value.count { it.isISOControl() } <= 1
}

internal class Wire(private val data: ByteArray) {
    data class Field(
        val number: Int,
        val wire: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    )

    private var p = 0

    fun fields(): List<Field> {
        val out = mutableListOf<Field>()
        while (p < data.size) {
            val tag = readVarint() ?: break
            val number = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            if (number <= 0) break
            when (wire) {
                0 -> out += Field(number, wire, varint = readVarint() ?: break)
                1 -> {
                    if (p + 8 > data.size) break
                    p += 8
                    out += Field(number, wire)
                }
                2 -> {
                    val length = (readVarint() ?: break).toInt()
                    if (length < 0 || p + length > data.size) break
                    out += Field(number, wire, bytes = data.copyOfRange(p, p + length))
                    p += length
                }
                5 -> {
                    if (p + 4 > data.size) break
                    p += 4
                    out += Field(number, wire)
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (p < data.size && shift < 64) {
            val value = data[p++].toInt() and 0xff
            result = result or ((value and 0x7f).toLong() shl shift)
            if (value and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}
