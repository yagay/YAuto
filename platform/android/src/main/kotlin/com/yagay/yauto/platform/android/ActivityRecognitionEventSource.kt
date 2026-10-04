package com.yagay.yauto.platform.android

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.google.android.gms.location.SleepSegmentRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

data class SleepClassificationSnapshot(
    val confidence: Int,
    val light: Int,
    val motion: Int,
    val sourceTimestampMs: Long,
    val receivedEpochMs: Long,
)

object ActivityRecognitionRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null
    @Volatile private var latestSleepClassification: SleepClassificationSnapshot? = null

    fun attach(value: RuntimeEventEmitter?) {
        emitter = value
    }

    internal fun emit(event: RuntimeEvent) {
        emitter?.emit(event)
    }

    internal fun updateSleepClassification(value: SleepClassificationSnapshot) {
        latestSleepClassification = value
    }

    fun sleepClassification(): SleepClassificationSnapshot? = latestSleepClassification
}

class YAutoActivityRecognitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val value = intent ?: return

        if (ActivityTransitionResult.hasResult(value)) {
            val result = ActivityTransitionResult.extractResult(value)
            result?.transitionEvents.orEmpty().forEach { event ->
                ActivityRecognitionRuntimeBridge.emit(
                    RuntimeEvent(
                        typeId = "android.event.activity_recognition",
                        payload = mapOf(
                            "activity" to ConfigValue.StringValue(activityName(event.activityType)),
                            "activityType" to ConfigValue.NumberValue(event.activityType.toDouble()),
                            "transition" to ConfigValue.StringValue(
                                if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) "enter"
                                else "exit"
                            ),
                            "elapsedRealtimeNanos" to ConfigValue.NumberValue(event.elapsedRealTimeNanos.toDouble()),
                        ),
                        source = "google.activity_transition",
                    )
                )
            }
        }

        if (ActivityRecognitionResult.hasResult(value)) {
            val result = ActivityRecognitionResult.extractResult(value)
            val activity = result?.mostProbableActivity
            if (activity != null) {
                ActivityRecognitionRuntimeBridge.emit(
                    RuntimeEvent(
                        typeId = "android.event.activity_recognition",
                        payload = mapOf(
                            "activity" to ConfigValue.StringValue(activityName(activity.type)),
                            "activityType" to ConfigValue.NumberValue(activity.type.toDouble()),
                            "confidence" to ConfigValue.NumberValue(activity.confidence.toDouble()),
                            "transition" to ConfigValue.StringValue("sample"),
                        ),
                        source = "google.activity_recognition",
                    )
                )
            }
        }

        if (SleepClassifyEvent.hasEvents(value)) {
            SleepClassifyEvent.extractEvents(value).forEach { event ->
                val snapshot = SleepClassificationSnapshot(
                    confidence = event.confidence,
                    light = event.light,
                    motion = event.motion,
                    sourceTimestampMs = event.timestampMillis,
                    receivedEpochMs = System.currentTimeMillis(),
                )
                ActivityRecognitionRuntimeBridge.updateSleepClassification(snapshot)
                ActivityRecognitionRuntimeBridge.emit(
                    RuntimeEvent(
                        typeId = "android.event.sleep_classification",
                        payload = mapOf(
                            "confidence" to ConfigValue.NumberValue(event.confidence.toDouble()),
                            "light" to ConfigValue.NumberValue(event.light.toDouble()),
                            "motion" to ConfigValue.NumberValue(event.motion.toDouble()),
                            "timestampMs" to ConfigValue.NumberValue(event.timestampMillis.toDouble()),
                        ),
                        source = "google.sleep_classify",
                    )
                )
            }
        }

        if (SleepSegmentEvent.hasEvents(value)) {
            SleepSegmentEvent.extractEvents(value).forEach { event ->
                ActivityRecognitionRuntimeBridge.emit(
                    RuntimeEvent(
                        typeId = "android.event.sleep_transition",
                        payload = mapOf(
                            "event" to ConfigValue.StringValue("segment"),
                            "startTimeMillis" to ConfigValue.NumberValue(event.startTimeMillis.toDouble()),
                            "endTimeMillis" to ConfigValue.NumberValue(event.endTimeMillis.toDouble()),
                            "status" to ConfigValue.NumberValue(event.status.toDouble()),
                            "durationMillis" to ConfigValue.NumberValue(
                                (event.endTimeMillis - event.startTimeMillis).coerceAtLeast(0L).toDouble()
                            ),
                        ),
                        source = "google.sleep_segments",
                    )
                )
            }
        }
    }

    private fun activityName(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "in_vehicle"
        DetectedActivity.ON_BICYCLE -> "on_bicycle"
        DetectedActivity.ON_FOOT -> "on_foot"
        DetectedActivity.RUNNING -> "running"
        DetectedActivity.STILL -> "still"
        DetectedActivity.TILTING -> "tilting"
        DetectedActivity.WALKING -> "walking"
        else -> "unknown"
    }
}

class ActivityRecognitionEventSource(
    context: Context,
) : AndroidEventSource {
    override val id: String = "android.activity_recognition"
    private val context = context.applicationContext
    private val client = ActivityRecognition.getClient(this.context)
    private val started = AtomicBoolean(false)
    private var pendingIntent: PendingIntent? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        ActivityRecognitionRuntimeBridge.attach(emitter)
        if (!hasPermission()) return

        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, YAutoActivityRecognitionReceiver::class.java)
                .setAction(ACTION_ACTIVITY_RECOGNITION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        pendingIntent = pending

        runCatching { client.requestActivityUpdates(ACTIVITY_INTERVAL_MS, pending) }
        runCatching { client.requestActivityTransitionUpdates(transitionRequest(), pending) }
        runCatching {
            client.requestSleepSegmentUpdates(
                pending,
                SleepSegmentRequest.getDefaultSleepSegmentRequest(),
            )
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        pendingIntent?.let { pending ->
            runCatching { client.removeActivityUpdates(pending) }
            runCatching { client.removeActivityTransitionUpdates(pending) }
            runCatching { client.removeSleepSegmentUpdates(pending) }
            pending.cancel()
        }
        pendingIntent = null
        ActivityRecognitionRuntimeBridge.attach(null)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun transitionRequest(): ActivityTransitionRequest {
        val types = listOf(
            DetectedActivity.IN_VEHICLE,
            DetectedActivity.ON_BICYCLE,
            DetectedActivity.ON_FOOT,
            DetectedActivity.RUNNING,
            DetectedActivity.STILL,
            DetectedActivity.WALKING,
        )
        val transitions = buildList {
            types.forEach { type ->
                add(
                    ActivityTransition.Builder()
                        .setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                        .build()
                )
                add(
                    ActivityTransition.Builder()
                        .setActivityType(type)
                        .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                        .build()
                )
            }
        }
        return ActivityTransitionRequest(transitions)
    }

    companion object {
        private const val REQUEST_CODE = 9131
        private const val ACTIVITY_INTERVAL_MS = 10_000L
        const val ACTION_ACTIVITY_RECOGNITION = "com.yagay.yauto.ACTIVITY_RECOGNITION"
    }
}

private fun activityName(type: Int): String = when (type) {
    DetectedActivity.IN_VEHICLE -> "in_vehicle"
    DetectedActivity.ON_BICYCLE -> "on_bicycle"
    DetectedActivity.ON_FOOT -> "on_foot"
    DetectedActivity.RUNNING -> "running"
    DetectedActivity.STILL -> "still"
    DetectedActivity.TILTING -> "tilting"
    DetectedActivity.WALKING -> "walking"
    else -> "unknown"
}
