package com.yagay.yauto.platform.android

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech kept in its own pack so audio automation remains independently maintainable. */
class AndroidSpeechFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.speech"
    private val context = context.applicationContext
    private val controller = SpeechController(this.context)
    private val voiceInput = VoiceInputController(this.context)

    override fun install(registry: FeatureRegistry) {
        registerVoiceSearch(registry)
        registerWebSearch(registry)
        registerAssistant(registry)
        registerVoiceInput(registry)
        val speakingEvaluator = ConditionEvaluator { feature, _ ->
            controller.isSpeaking() == feature.config.boolean("value", true)
        }
        val speakingState = FeatureDescriptor(
            FeatureId("android.state.tts_speaking"),
            FeatureKind.STATE,
            "TTS speaking",
            "Check whether YAuto's Android text-to-speech engine is currently speaking",
            FeatureCategory.AUDIO,
            fields = listOf(FieldSchema.Toggle("value", "Speaking")),
            keywords = setOf("tts", "speaking", "speech", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerState(speakingState, speakingEvaluator)
        registry.registerCondition(
            speakingState.copy(
                id = FeatureId("android.condition.tts_speaking"),
                kind = FeatureKind.CONDITION,
            ),
            speakingEvaluator,
        )
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.tts.speak"),
                FeatureKind.ACTION,
                "Speak text",
                "Speak text using the Android text-to-speech engine",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("languageTag", "Language tag"),
                    FieldSchema.Number("rate", "Speech rate", min = 0.1, max = 3.0),
                    FieldSchema.Number("pitch", "Pitch", min = 0.1, max = 2.0),
                    FieldSchema.Toggle("waitForCompletion", "Wait for speech to finish"),
                ),
                keywords = setOf("tts", "text to speech", "speak", "voice", "speech"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            if (text.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.tts_text_empty"))
            }
            val rate = (feature.config["rate"].numberOrNull() ?: 1.0).toFloat().coerceIn(0.1f, 3.0f)
            val pitch = (feature.config["pitch"].numberOrNull() ?: 1.0).toFloat().coerceIn(0.1f, 2.0f)
            val languageTag = feature.config.string("languageTag").resolveVariables(ctx.variables).trim()
            val wait = feature.config.boolean("waitForCompletion", true)
            controller.speak(text, languageTag, rate, pitch, wait)
        }
    }
    private fun registerVoiceInput(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.voice_input.capture"),
                FeatureKind.ACTION,
                "Capture voice input",
                "Listen with Android SpeechRecognizer and return the best recognized phrase plus alternatives",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Text("languageTag", "Language tag"),
                    FieldSchema.Number("maxResults", "Maximum results", min = 1.0, max = 20.0),
                    FieldSchema.Duration("timeoutMs", "Recognition timeout"),
                    FieldSchema.Variable("resultVariable", "Store recognition object"),
                ),
                accessRequirements = setOf(AccessRequirement.RECORD_AUDIO),
                keywords = setOf("voice input", "speech recognition", "microphone", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val language = feature.config.string("languageTag").resolveVariables(ctx.variables).trim()
            val maxResults = (feature.config["maxResults"].numberOrNull() ?: 5.0).toInt().coerceIn(1, 20)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 30_000.0).toLong().coerceIn(1_000L, 120_000L)
            val phrases = voiceInput.capture(language, maxResults, timeout)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "Speech recognition failed or timed out"))
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "text" to ConfigValue.StringValue(phrases.firstOrNull().orEmpty()),
                    "alternatives" to ConfigValue.ListValue(phrases.map(ConfigValue::StringValue)),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(phrases.isNotEmpty(), output)
        }
    }

    private fun registerVoiceSearch(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.voice_search.open"),
                FeatureKind.ACTION,
                "Open voice search",
                "Open the system voice-search experience",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("prompt", "Voice-search prompt"),
                    FieldSchema.Text("languageTag", "Language tag"),
                ),
                keywords = setOf("voice search", "search", "speech", "assistant", "MacroDroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val prompt = feature.config.string("prompt").resolveVariables(ctx.variables).trim()
            val language = feature.config.string("languageTag").resolveVariables(ctx.variables).trim()
            launchExternal(
                Intent(RecognizerIntent.ACTION_WEB_SEARCH).apply {
                    if (prompt.isNotBlank()) putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                    if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                },
                feature.typeId,
            )
        }
    }

    private fun registerWebSearch(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.web_search.open"),
                FeatureKind.ACTION,
                "Open web search",
                "Open the system web-search handler with a text query",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.Text("query", "Search query", true)),
                fieldBehaviors = mapOf("query" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("web search", "search", "query", "browser"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val query = feature.config.string("query").resolveVariables(ctx.variables).trim()
            if (query.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            }
            launchExternal(
                Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
                feature.typeId,
            )
        }
    }

    private fun registerAssistant(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.assistant.open"),
                FeatureKind.ACTION,
                "Open digital assistant",
                "Open the current Android assistant using the standard assist intent",
                FeatureCategory.APP,
                keywords = setOf("assistant", "voice assistant", "assist", "Tasker"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            launchExternal(Intent(Intent.ACTION_ASSIST), feature.typeId)
        }
    }

    private fun launchExternal(intent: Intent, operationId: String): ActionExecutionResult = runCatching {
        val launch = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch.resolveActivity(context.packageManager) == null) {
            return@runCatching ActionExecutionResult(false, message = userText("feature.no_compatible_app"))
        }
        context.startActivity(launch)
        ActionExecutionResult(true)
    }.getOrElse {
        ActionExecutionResult(false, message = userText("feature.operation_failed", operationId))
    }
}

private class SpeechController(private val context: Context) {
    private val initMutex = Mutex()
    private val speakMutex = Mutex()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile private var engine: TextToSpeech? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) {
            utteranceId?.let { completions.remove(it)?.complete(true) }
        }
        @Deprecated("Deprecated by Android")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { completions.remove(it)?.complete(false) }
        }
        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { completions.remove(it)?.complete(false) }
        }
        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { completions.remove(it)?.complete(false) }
        }
    }

    fun isSpeaking(): Boolean = runCatching { engine?.isSpeaking == true }.getOrDefault(false)

    suspend fun speak(
        text: String,
        languageTag: String,
        rate: Float,
        pitch: Float,
        waitForCompletion: Boolean,
    ): ActionExecutionResult = speakMutex.withLock {
        val tts = ensureEngine()
            ?: return@withLock ActionExecutionResult(false, message = userText("feature.tts_unavailable"))

        val setup = runCatching {
            if (languageTag.isNotBlank()) {
                val locale = Locale.forLanguageTag(languageTag)
                require(locale.language.isNotBlank()) { "Invalid language tag" }
                val availability = tts.setLanguage(locale)
                require(availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED) {
                    "TTS language is unavailable"
                }
            }
            require(tts.setSpeechRate(rate) == TextToSpeech.SUCCESS) { "Unable to set speech rate" }
            require(tts.setPitch(pitch) == TextToSpeech.SUCCESS) { "Unable to set speech pitch" }
        }.exceptionOrNull()
        if (setup != null) {
            return@withLock ActionExecutionResult(false, message = userText("feature.operation_failed", setup.message ?: setup.javaClass.simpleName))
        }

        val utteranceId = "yauto-${UUID.randomUUID()}"
        val completion = if (waitForCompletion) CompletableDeferred<Boolean>().also {
            completions[utteranceId] = it
        } else null

        val speakResult = runCatching {
            withContext(Dispatchers.Main.immediate) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }.getOrElse {
            completions.remove(utteranceId)
            return@withLock ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
        }
        if (speakResult != TextToSpeech.SUCCESS) {
            completions.remove(utteranceId)
            return@withLock ActionExecutionResult(false, message = userText("feature.tts_failed"))
        }
        if (completion == null) return@withLock ActionExecutionResult(true, ConfigValue.StringValue(utteranceId))

        val finished = withTimeoutOrNull(MAX_WAIT_MS) { completion.await() }
        completions.remove(utteranceId)
        when (finished) {
            true -> ActionExecutionResult(true, ConfigValue.StringValue(utteranceId))
            false -> ActionExecutionResult(false, message = userText("feature.tts_failed"))
            null -> ActionExecutionResult(false, message = userText("feature.tts_timeout"))
        }
    }

    private suspend fun ensureEngine(): TextToSpeech? {
        engine?.let { return it }
        return initMutex.withLock {
            engine?.let { return@withLock it }
            val initialized = CompletableDeferred<Int>()
            val created = withContext(Dispatchers.Main.immediate) {
                TextToSpeech(context) { status -> initialized.complete(status) }
            }
            val status = withTimeoutOrNull(INIT_TIMEOUT_MS) { initialized.await() }
            if (status != TextToSpeech.SUCCESS) {
                withContext(Dispatchers.Main.immediate) { created.shutdown() }
                null
            } else {
                created.setOnUtteranceProgressListener(listener)
                engine = created
                created
            }
        }
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val MAX_WAIT_MS = 10 * 60_000L
    }
}


private class VoiceInputController(private val context: Context) {
    private val mutex = Mutex()

    suspend fun capture(languageTag: String, maxResults: Int, timeoutMs: Long): List<String>? = mutex.withLock {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withLock null
        val result = CompletableDeferred<List<String>?>()
        var recognizer: SpeechRecognizer? = null

        val created = runCatching {
            withContext(Dispatchers.Main.immediate) {
                SpeechRecognizer.createSpeechRecognizer(context).also { engine ->
                    recognizer = engine
                    engine.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onError(error: Int) {
                            if (!result.isCompleted) result.complete(null)
                        }
                        override fun onResults(results: Bundle?) {
                            val values = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.take(maxResults)
                                .orEmpty()
                            if (!result.isCompleted) result.complete(values)
                        }
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    engine.startListening(
                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults)
                            if (languageTag.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        }
                    )
                }
            }
        }.isSuccess
        if (!created) return@withLock null

        val values = withTimeoutOrNull(timeoutMs) { result.await() }
        withContext(Dispatchers.Main.immediate) {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
        }
        values
    }
}
