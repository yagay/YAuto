package com.yagay.yauto.importer.tasker

import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.PredicateNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskerImporterTest {
    @Test fun `conditional action stays preserved with complete XML attributes and text`() {
        val longValue = "x".repeat(1000) + " &amp; &lt;test&gt;"
        val xml = """<TaskerData><Task sr="task1"><id>1</id><nme>Guarded</nme><Action sr="act0" extra="kept"><code>548</code><Str sr="arg0">$longValue</Str><ConditionList sr="if"><Condition sr="c0"><lhs>%test</lhs><op>0</op><rhs>yes</rhs></Condition></ConditionList></Action></Task></TaskerData>"""
        val result = TaskerImporter().import(ImportInput("guarded.xml", null, xml.toByteArray()))
        assertTrue(result.success)
        val guarded = result.bundle.flows.single().actions.single() as ActionNode.If
        val condition = guarded.condition as PredicateNode.Condition
        assertEquals("tasker.condition.compare", condition.feature.typeId)
        val feature = (guarded.thenActions.single() as ActionNode.Action).feature
        assertEquals("android.toast.show", feature.typeId)
        val raw = (feature.config["source.raw"] as ConfigValue.StringValue).value
        assertTrue(raw.contains("extra=\"kept\""))
        assertTrue(raw.contains("<Condition sr=\"c0\">"))
        assertTrue(raw.contains("x".repeat(1000)))
        assertTrue(raw.contains("&amp;"))
    }
    @Test
    fun `maps stable Tasker actions and perform task to native YAuto nodes`() {
        val xml = """
            <TaskerData sr="" tv="6.3">
              <Task sr="task1">
                <id>1</id><nme>Main</nme>
                <Action sr="act0"><code>30</code><Int sr="arg0" val="0"/><Int sr="arg1" val="0"/><Int sr="arg2" val="250"/><Int sr="arg3" val="2"/><Int sr="arg4" val="0"/></Action>
                <Action sr="act1"><code>548</code><Str sr="arg0">Hello %name</Str></Action>
                <Action sr="act2"><code>20</code><App sr="arg0"><appPkg>com.example.app</appPkg></App></Action>
                <Action sr="act3"><code>547</code><Str sr="arg0">%name</Str><Str sr="arg1">World</Str></Action>
                <Action sr="act4"><code>105</code><Str sr="arg0">%name</Str></Action>
                <Action sr="act5"><code>123</code><Str sr="arg0">id</Str><Int sr="arg1" val="1"/><Int sr="arg2" val="10"/><Str sr="arg3">%shell_out</Str><Str sr="arg4">%shell_err</Str><Str sr="arg5"/></Action>
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
            listOf("core.delay", "android.toast.show", "android.app.launch", "variable.set", "android.clipboard.set", "android.shell.execute"),
            main.actions.take(6).map { (it as ActionNode.Action).feature.typeId },
        )
        val delay = (main.actions[0] as ActionNode.Action).feature
        assertEquals(2250.0, (delay.config["durationMs"] as ConfigValue.NumberValue).value, 0.0)
        val shell = (main.actions[5] as ActionNode.Action).feature
        assertEquals(ConfigValue.NumberValue(10_000.0), shell.config["timeoutMs"])
        assertEquals(ConfigValue.StringValue("%shell_out"), shell.config["stdoutVariable"])
        assertEquals(ConfigValue.StringValue("%shell_err"), shell.config["stderrVariable"])
        val call = main.actions[6] as ActionNode.CallFlow
        assertEquals(result.bundle.flows.first { it.name == "Helper" }.id, call.flowId)
        assertEquals("%name", (call.input["%par1"] as ConfigValue.StringValue).value)
        assertEquals("%return", call.resultVariable)
    }
    @Test
    fun `maps write file variable clear non-root shell and intent event natively`() {
        val xml = """
            <TaskerData sr="" tv="6.6">
              <Profile sr="prof1" ve="2">
                <id>10</id><mid0>1</mid0><nme>Intent</nme>
                <Event sr="con0" ve="2">
                  <code>599</code><Str sr="arg0">com.example.DEMO</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Str sr="arg3"/><Str sr="arg4"/>
                </Event>
              </Profile>
              <Task sr="task1">
                <id>1</id><nme>Main</nme>
                <Action sr="act0"><code>410</code><Str sr="arg0">Download/result.txt</Str><Str sr="arg1">hello</Str><Int sr="arg2" val="1"/><Int sr="arg3" val="1"/><Int sr="arg4" val="0"/></Action>
                <Action sr="act1"><code>549</code><Str sr="arg0">%temp</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="0"/><Int sr="arg3" val="0"/></Action>
                <Action sr="act2"><code>123</code><Str sr="arg0">echo test</Str><Int sr="arg1" val="0"/><Int sr="arg2" val="5"/><Str sr="arg3">%out</Str><Str sr="arg4">%err</Str><Str sr="arg5"/></Action>
              </Task>
            </TaskerData>
        """.trimIndent()

        val result = TaskerImporter().import(ImportInput("native.prj.xml", "text/xml", xml.toByteArray()))
        assertTrue(result.success)
        val event = result.bundle.automations.single().activation.events.single()
        assertEquals("android.event.broadcast", event.typeId)
        assertEquals(ConfigValue.StringValue("com.example.DEMO"), event.config["action"])

        val main = result.bundle.flows.single { it.name == "Main" }
        val features = main.actions.map { (it as ActionNode.Action).feature }
        assertEquals(listOf("file.write_text", "variable.clear", "android.shell.execute_unprivileged"), features.map { it.typeId })
        assertEquals(ConfigValue.BooleanValue(true), features[0].config["append"])
        assertEquals(ConfigValue.StringValue("hello\n"), features[0].config["text"])
        assertEquals(ConfigValue.StringValue("%temp"), features[1].config["name"])
        assertEquals(ConfigValue.NumberValue(5_000.0), features[2].config["timeoutMs"])
        assertEquals(ConfigValue.StringValue("%out"), features[2].config["stdoutVariable"])
        assertEquals(ConfigValue.StringValue("%err"), features[2].config["stderrVariable"])
    }

}
