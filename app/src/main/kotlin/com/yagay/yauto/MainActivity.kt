package com.yagay.yauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.yagay.yauto.ui.design.YAutoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntimeService.start(this)
        val graph = (application as YAutoApplication).graph
        setContent {
            YAutoTheme {
                YAutoAppScreen(graph)
            }
        }
    }
}
