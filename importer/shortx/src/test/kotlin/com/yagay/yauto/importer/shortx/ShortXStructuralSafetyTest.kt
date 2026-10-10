package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ActionFailurePolicy
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXStructuralSafetyTest {
    @Test fun `clean control actions still convert into native nodes`() {
        assertTrue(action("BreakActionExecute", byteArrayOf()) is ActionNode.Break)
        assertTrue(action("StopAllActions", byteArrayOf()) is ActionNode.Return)
        val returned = action("SetFunctionReturnValue", field(1, "result")) as ActionNode.Return
        assertEquals(ConfigValue.StringValue("result"), returned.value)
        assertTrue(action("ExecuteFunction", field(1, "flow")) is ActionNode.CallFlow)
    }

    @Test fun `unknown business fields cannot be silently discarded`() {
        assertCompat("BreakActionExecute", varintField(1, 1))
        assertCompat("StopAllActions", field(3, "unexpected"))
        assertCompat("SetFunctionReturnValue", msg(field(1, "ok"), varintField(2, 1)))
        assertCompat("ExecuteFunction", msg(field(1, "flow"), field(3, "unknown")))
        assertCompat("IfThenElse", msg(field(1, any("TRUE", byteArrayOf())), varintField(30, 1)))
    }

    @Test fun `structural actions preserve disabled notes and break on error metadata`() {
        val disabled = action("BreakActionExecute", varintField(98, 1)) as ActionNode.Action
        assertEquals("compat.source.action", disabled.feature.typeId)
        assertEquals(false, disabled.enabled)
        val error = action("StopAllActions", varintField(97, 1)) as ActionNode.Action
        assertEquals(ActionFailurePolicy.STOP, error.failurePolicy)
        assertEquals("compat.source.action", error.feature.typeId)
        val noted = action("SetFunctionReturnValue", msg(field(1, "ok"), field(99, "keep note"))) as ActionNode.Action
        assertEquals("keep note", noted.comment)
        assertCompat("BreakActionExecute", varintField(98, 2))
        assertCompat("BreakActionExecute", field(96, "future"))
    }

    @Test fun `invalid wait duration and duplicate flow params remain source nodes`() {
        val cond = field(1, any("TRUE", byteArrayOf()))
        assertCompat("WaitUtilConditionMatch", msg(cond, varintField(4, 0)))
        assertCompat("WaitUtilConditionMatch", msg(cond, varintField(4, 86_400_001)))
        assertCompat("WaitUtilConditionMatch", msg(cond, field(10, "invalid")))
        val param = msg(field(1, "x"), field(2, "a"))
        assertCompat("ExecuteFunction", msg(field(1, "flow"), field(2, param), field(2, param)))
        assertCompat("ExecuteFunction", msg(field(1, "flow"), field(2, msg(field(1, "x"), field(3, "new")))))
    }

    private fun assertCompat(name: String, payload: ByteArray) {
        assertEquals("compat.source.action", (action(name, payload) as ActionNode.Action).feature.typeId)
    }

    private fun action(name: String, payload: ByteArray): ActionNode {
        val rule = msg(field(3, any(name, payload)), field(4, "structural"), field(9, "Structural"), varintField(11, 1))
        val result = ShortXImporter().import(ImportInput(name + ".rule", null, rule))
        assertTrue(result.issues.joinToString { it.message }, result.success)
        return result.bundle.automations.single().onEvent.single()
    }

    private fun any(name: String, payload: ByteArray): ByteArray =
        msg(field(1, "type.googleapis.com/tornaco.apps.shortx.core.proto.action." + name), field(2, payload))
    private fun msg(vararg chunks: ByteArray) =
        ByteArrayOutputStream().apply { chunks.forEach(::write) }.toByteArray()
    private fun field(number: Int, value: String) = field(number, value.toByteArray())
    private fun field(number: Int, value: ByteArray) =
        msg(varint(((number shl 3) or 2).toLong()), varint(value.size.toLong()), value)
    private fun varintField(number: Int, value: Long) = msg(varint((number shl 3).toLong()), varint(value))
    private fun varint(value: Long): ByteArray {
        var remaining = value
        val bytes = ByteArrayOutputStream()
        do {
            var item = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) item = item or 0x80
            bytes.write(item)
        } while (remaining != 0L)
        return bytes.toByteArray()
    }
}
