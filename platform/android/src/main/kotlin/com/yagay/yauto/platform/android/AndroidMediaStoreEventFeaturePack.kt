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
        register(registry, "android.event.media_store_inserted", "Media inserted", "Run when MediaStore reports a newly inserted media item")
        register(registry, "android.event.media_store_updated", "Media updated", "Run when MediaStore reports an updated media item")
        register(registry, "android.event.media_store_deleted", "Media deleted", "Run when MediaStore reports a deleted media item")
        register(registry, "android.event.media_store_changed", "MediaStore changed", "Run on any MediaStore content change")
    }

    private fun register(registry: FeatureRegistry, typeId: String, title: String, description: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title, description,
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Choice("collection", "Collection", true, listOf("any", "images", "video", "audio", "files")),
                    FieldSchema.Text("uriContains", "URI contains"),
                ),
                keywords = setOf("mediastore", "photo", "video", "audio", "insert", "delete", "gallery"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            val collection = feature.config.string("collection", "any")
            if (collection != "any" && ctx.event.payload.string("collection") != collection) return@registerEvent false
            val uriContains = feature.config.string("uriContains")
            uriContains.isBlank() || ctx.event.payload.string("uri").contains(uriContains, ignoreCase = true)
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
        val payload = mapOf(
            "uri" to ConfigValue.StringValue(uri.toString()),
            "collection" to ConfigValue.StringValue(collection),
            "flags" to ConfigValue.NumberValue(flags.toDouble()),
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
