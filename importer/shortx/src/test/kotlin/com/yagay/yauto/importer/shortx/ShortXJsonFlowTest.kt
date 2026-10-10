package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.PredicateNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ShortXJsonFlowTest {
    @Test fun `JSON native IF and NONE boolean operator map without creating duplicate feature IDs`() {
        val rule = """{"id":"json-if","title":"If","actions":[{
          "@type":"type.googleapis.com/IfThenElse",
          "_if":[{"@type":"type.googleapis.com/TRUE"}],
          "_ifCondOp":"NONE",
          "_ifCondOpPayload":{"expression":""},
          "_ifActions":[{"@type":"type.googleapis.com/ShowToast","message":"matched"}],
          "_elseActions":[{"@type":"type.googleapis.com/NoAction"}],
          "_ifActionAsyncMode":"ActionAsyncMode_Sync",
          "_elseActionAsyncMode":"ActionAsyncMode_Sync"
        }]}"""
        val node = jsonActions(rule).single() as ActionNode.If
        assertTrue(node.condition is PredicateNode.None)
        assertEquals("android.toast.show", (node.thenActions.single() as ActionNode.Action).feature.typeId)
        assertEquals("core.noop", (node.elseActions.single() as ActionNode.Action).feature.typeId)
    }

    @Test fun `JSON sequential while and validated flow calls become native nodes`() {
        val rule = """{"id":"json-flow","title":"Flow","actions":[
            {"@type":"type.googleapis.com/WhileLoop",
             "conditions":[{"@type":"type.googleapis.com/FALSE"}],"condOp":"NONE",
             "actions":[{"@type":"type.googleapis.com/NoAction"}],
             "delay":0,"repeatTimes":0,"actionAsyncMode":"ActionAsyncMode_Sync"},
            {"@type":"type.googleapis.com/ExecuteFunction","functionId":"worker",
             "funcParameterInputs":[{"name":"arg","value":"hello"}]},
            {"@type":"type.googleapis.com/FromDA","daId":"quick"},
            {"@type":"type.googleapis.com/SetFunctionReturnValue","value":"done"},
            {"@type":"type.googleapis.com/StopAllActions"},
            {"@type":"type.googleapis.com/BreakActionExecute","scope":"BreakActionExecuteScope_Current"}
        ]}"""
        val nodes = jsonActions(rule)
        val loop = nodes[0] as ActionNode.While
        assertTrue(loop.condition is PredicateNode.None)
        assertEquals("core.noop", (loop.actions.single() as ActionNode.Action).feature.typeId)
        val call = nodes[1] as ActionNode.CallFlow
        assertEquals("import-shortx-function-worker", call.flowId.value)
        assertEquals(ConfigValue.StringValue("hello"), call.input["arg"])
        assertEquals("import-shortx-da-quick", (nodes[2] as ActionNode.CallFlow).flowId.value)
        assertEquals(ConfigValue.StringValue("done"), (nodes[3] as ActionNode.Return).value)
        assertTrue(nodes[4] is ActionNode.Return)
        assertTrue(nodes[5] is ActionNode.Break)
    }

    @Test fun `unrepresentable source context remains compatible and preserves original action`() {
        val cases = listOf(
          """"@type":"type.googleapis.com/IfThenElse","_if":[{"@type":"type.googleapis.com/TRUE"}],"_ifCondOp":"MVEL","_ifActions":[]""",
          """"@type":"type.googleapis.com/IfThenElse","_if":[{"@type":"type.googleapis.com/TRUE"}],"_ifActionAsyncMode":"ActionAsyncMode_Async""",
          """"@type":"type.googleapis.com/WhileLoop","conditions":[{"@type":"type.googleapis.com/TRUE"}],"delay":100""",
          """"@type":"type.googleapis.com/StopAllActions","note":"please preserve""",
          """"@type":"type.googleapis.com/BreakActionExecute","scope":"BreakActionExecuteScope_Root"""",
          """"@type":"type.googleapis.com/ExecuteFunction","functionId":"f","funcParameterInputs":[{"name":"a","value":"1"},{"name":"a","value":"2"}]""",
          """"@type":"type.googleapis.com/IfThenElse","_if":[{"@type":"type.googleapis.com/TRUE"}],"_ifCondOp":"NONE","futureOption":1""",
          """"@type":"type.googleapis.com/SetFunctionReturnValue","value":"x","id":"A-111""""
        )
        cases.forEachIndexed { i, body ->
            val node = jsonActions("""{"title":"case","actions":[{$body}]}""").single() as ActionNode.Action
            assertEquals("case index $i", "compat.source.action", node.feature.typeId)
        }
    }

    @Test fun `protobuf NONE operator matches documented semantics`() {
        val condition = any("TRUE", byteArrayOf())
        val payload = msg(field(1, condition), varintField(2, 2))
        val node = binaryAction("IfThenElse", payload) as ActionNode.If
        assertTrue(node.condition is PredicateNode.None)
    }

    private fun jsonActions(rule: String): List<ActionNode> {
        val result = ShortXImporter().import(ImportInput("flow.json", "application/json", rule.toByteArray()))
        assertTrue(result.issues.joinToString { it.message }, result.success)
        return result.bundle.automations.single().onEvent
    }

    private fun binaryAction(name: String, payload: ByteArray): ActionNode {
        val bytes = msg(field(3, any(name, payload)), field(4, "binary"), field(9, "Binary"), varintField(11, 1))
        val result = ShortXImporter().import(ImportInput(name + ".rule", null, bytes))
        assertTrue(result.issues.joinToString { it.message }, result.success)
        return result.bundle.automations.single().onEvent.single()
    }

    private fun any(name: String, payload: ByteArray) =
        msg(field(1, "type.googleapis.com/" + name), field(2, payload))
    private fun msg(vararg items: ByteArray) =
        ByteArrayOutputStream().apply { items.forEach(::write) }.toByteArray()
    private fun field(n: Int, value: String) = field(n, value.toByteArray())
    private fun field(n: Int, value: ByteArray) =
        msg(varint(((n shl 3) or 2).toLong()), varint(value.size.toLong()), value)
    private fun varintField(n: Int, value: Long) = msg(varint((n shl 3).toLong()), varint(value))
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        do {
            var b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            out.write(b)
        } while (v != 0L)
        return out.toByteArray()
    }
}
