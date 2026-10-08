package com.yagay.yauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yagay.yauto.ui.design.YAutoTheme

class MainActivity : ComponentActivity() {
    private val graph: AppGraph
        get() = (application as YAutoApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YAutoTheme {
                YAutoAppScreen(graph)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (FeatureHealthScanner.autoScanEnabled(this)) {
            lifecycleScope.launch {
                delay(1_000L)
                graph.featureHealth.requestScan()
            }
        }
    }
}
