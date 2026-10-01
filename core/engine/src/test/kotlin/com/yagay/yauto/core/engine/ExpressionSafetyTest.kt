package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ConfigValue
import org.junit.Assert.*
import org.junit.Test

class ExpressionSafetyTest {
    private val engine = SimpleExpressionEngine()
    private val variables = RuntimeVariables(mapOf("message" to ConfigValue.StringValue("a && b || c")))

    @Test fun `and binds before or and parentheses override precedence`() {
        assertTrue(engine.evaluateBoolean("true || false && false", variables))
        assertFalse(engine.evaluateBoolean("(true || false) && false", variables))
        assertTrue(engine.evaluateBoolean("!(false || false) && true", variables))
    }

    @Test fun `quoted operators remain string data`() {
        assertTrue(engine.evaluateBoolean("message == 'a && b || c'", variables))
        assertFalse(engine.evaluateBoolean("message != 'a && b || c'", variables))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unbalanced expression fails visibly`() { engine.evaluateBoolean("(true || false", variables) }
}
