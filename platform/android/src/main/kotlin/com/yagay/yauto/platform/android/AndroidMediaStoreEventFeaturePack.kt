package com.yagay.yauto.platform.android

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.util.concurrent.atomic.AtomicBoolean

class AndroidMediaStoreEventFeaturePack : FeaturePack {
    override val id: String = "android.media_store_events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.media_store_changed"),
                FeatureKind.EVENT,
                "MediaStore changed",
                "Run when MediaStore content is inserted, updated, deleted or otherwise changed",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Choice(
                        "changeType",
                        "Change type",
                        true,
                        listOf("any", "inserted", "updated", "deleted", "changed"),
                    ),
                    FieldSchema.Choice(
                        "collection",
                        "Collection",
                        true,
                        listOf("any", "images", "video", "audio", "files"),
                    ),
                    FieldSchema.Text("uriContains", "URI contains"),
                ),
                keywords = setOf("mediastore", "photo", "video", "audio", "insert", "update", "delete", "gallery"),
                ownerPackId = id,
                aliases = setOf(
                    "android.event.media_store_inserted",
                    "android.event.media_store_updated",
                    "android.event.media_store_deleted",
                    "android.event.photo_taken",
                    "android.event.video_created",
                    "android.event.audio_created",
                ),
                aliasConfigDefaults = mapOf(
                    "android.event.media_store_inserted" to mapOf(
                        "changeType" to ConfigValue.StringValue("inserted"),
                    ),
                    "android.event.media_store_updated" to mapOf(
                        "changeType" to ConfigValue.StringValue("updated"),
                    ),
                    "android.event.media_store_deleted" to mapOf(
                        "changeType" to ConfigValue.StringValue("deleted"),
                    ),
                    "android.event.photo_taken" to mapOf(
                        "changeType" to ConfigValue.StringValue("inserted"),
                        "collection" to ConfigValue.StringValue("images"),
                    ),
                    "android.event.video_created" to mapOf(
                        "changeType" to ConfigValue.StringValue("inserted"),
                        "collection" to ConfigValue.StringValue("video"),
                    ),
                    "android.event.audio_created" to mapOf(
                        "changeType" to ConfigValue.StringValue("inserted"),
                        "collection" to ConfigValue.StringValue("audio"),
                    ),
                ),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.media_store_changed") return@registerEvent false
            val wantedType = feature.config.string("changeType", "any")
            val actualType = ctx.event.payload.string("changeType", "changed")
            if (wantedType != "any" && wantedType != actualType) return@registerEvent false

            val collection = feature.config.string("collection", "any")
            if (collection != "any" && ctx.event.payload.string("collection") != collection) return@registerEvent false

            val uriContains = feature.config.string("uriContains")
            uriContains.isBlank() ||
                ctx.event.payload.string("uri").contains(uriContains, ignoreCase = true)
        }
    }
}

class MediaStoreEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.media_store"
    private val resolver = context.applicationContext.contentResolver
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null
    private val observers = mutableListOf<ContentObserver>()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        register(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "images")
        register(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video")
        register(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio")
        register(MediaStore.Files.getContentUri("external"), "files")
    }

    private fun register(uri: Uri, collection: String) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, changedUri: Uri?) {
                emitChange(changedUri ?: uri, collection, 0)
            }

            override fun onChange(selfChange: Boolean, changedUri: Uri?, flags: Int) {
                emitChange(changedUri ?: uri, collection, flags)
            }
        }
        if (runCatching { resolver.registerContentObserver(uri, true, observer); true }.getOrDefault(false)) observers += observer
    }

    private fun emitChange(uri: Uri, collection: String, flags: Int) {
        val typeId = when {
            flags and ContentResolver.NOTIFY_INSERT != 0 -> "android.event.media_store_inserted"
            flags and ContentResolver.NOTIFY_DELETE != 0 -> "android.event.media_store_deleted"
            flags and ContentResolver.NOTIFY_UPDATE != 0 -> "android.event.media_store_updated"
            else -> "android.event.media_store_changed"
        }
        val changeType = when (typeId) {
            "android.event.media_store_inserted" -> "inserted"
            "android.event.media_store_updated" -> "updated"
            "android.event.media_store_deleted" -> "deleted"
            else -> "changed"
        }
        val payload = mapOf(
            "uri" to ConfigValue.StringValue(uri.toString()),
            "collection" to ConfigValue.StringValue(collection),
            "flags" to ConfigValue.NumberValue(flags.toDouble()),
            "changeType" to ConfigValue.StringValue(changeType),
        )
        emitter?.emit(RuntimeEvent(typeId = typeId, payload = payload, source = id))
        if (typeId != "android.event.media_store_changed") {
            emitter?.emit(RuntimeEvent(typeId = "android.event.media_store_changed", payload = payload, source = id))
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        observers.forEach { runCatching { resolver.unregisterContentObserver(it) } }
        observers.clear()
        emitter = null
    }
}
