package com.yagay.yauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import com.yagay.yauto.platform.android.VoiceInputRuntimeBridge
import java.util.Locale

class VoiceInputActivity : Activity() {
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("token").orEmpty()
        if (token.isBlank()) {
            finish()
            return
        }
        val prompt = intent.getStringExtra("prompt").orEmpty()
        val language = intent.getStringExtra("language").orEmpty()
        val recognizer = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        if (prompt.isNotBlank()) recognizer.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        if (language.isNotBlank()) {
            recognizer.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            recognizer.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
        } else {
            recognizer.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        runCatching { startActivityForResult(recognizer, REQUEST) }
            .onFailure {
                VoiceInputRuntimeBridge.complete(token, null)
                finish()
            }
    }

    @Deprecated("Activity result compatibility path")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            val result = if (resultCode == RESULT_OK) {
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            } else null
            VoiceInputRuntimeBridge.complete(token, result)
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing && token.isNotBlank()) {
            VoiceInputRuntimeBridge.complete(token, null)
        }
        super.onDestroy()
    }

    companion object { private const val REQUEST = 9107 }
}
