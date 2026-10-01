package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerImporterTest {
    @Test
    fun `maps stable Tasker actions and perform task to native YAuto nodes`() {
        val xml = """
            <TaskerData sr="" tv="6.3">
              <Task sr="task1">
                <id>1</id><nme>Main</nme>
                <Action sr="act0"><code>30</code><Int sr="arg0" val="250"/><Int sr="arg1" val="2"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/><Int sr="arg4" val="0"/></Action>
                <Action sr="act1"><code>548</code><Str sr="arg0">Hello %name</Str></Action>
                <Action sr="act2"><code>20</code><App sr="arg0"><appPkg>com.example.app</appPkg></App></Action>
                <Action sr="act3"><code>547</code><Str sr="arg0">%name</Str><Str sr="arg1">World</Str></Action>
                <Action sr="act4"><code>105</code><Str sr="arg0">%name</Str></Action>
                <Action sr="act5"><code>123</code><Str sr="arg0">id</Str><Int sr="arg2" val="1"/><Str sr="arg3">%shell_result</Str></Action>
                <Action sr="act6"><code>130</code><Str sr="arg0">Helper</Str><Int sr="arg1" val="10"/><Str sr="arg2">%name</Str><Str sr="arg3">fixed</Str><Str sr="arg4">%return</Str></Action>
              </Task>
              <Task sr="task2">
                <id>2</id><nme>Helper</nme>
                <Action sr="act0"><code>548</code><Str sr="arg0">%par1</Str></Action>
              </Task>
            </TaskerData>
        """.trimIndent()

        val result = TaskerImporter().import(ImportInput("demo.tsk.xml", "text/xml", xml.toByteArray()))

        assertTrue(result.success)
        assertEquals(2, result.bundle.flows.size)
        val main = result.bundle.flows.first { it.name == "Main" }
        assertEquals(
            listOf("core.delay", "android.toast.show", "android.app.launch", "variable.set", "android.clipboard.set", "system.shell.execute"),
            main.actions.take(6).map { (it as ActionNode.Action).feature.typeId },
        )
        val delay = (main.actions[0] as ActionNode.Action).feature
        assertEquals(2250.0, (delay.config["durationMs"] as ConfigValue.NumberValue).value, 0.0)
        val call = main.actions[6] as ActionNode.CallFlow
        assertEquals(result.bundle.flows.first { it.name == "Helper" }.id, call.flowId)
        assertEquals("%name", (call.input["%par1"] as ConfigValue.StringValue).value)
        assertEquals("%return", call.resultVariable)
    }
}
