package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

class AndroidSolarFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.solar"
    private val context = context.applicationContext
    private val locations = this.context.getSystemService(LocationManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerSolarTimes(registry)
        registerDaylight(registry, FeatureKind.STATE, "android.state.daylight")
        registerDaylight(registry, FeatureKind.CONDITION, "android.condition.daylight")
        registerSolarEvent(registry)
    }

    private fun locationFields() = listOf(
        FieldSchema.Toggle("useLastKnown", "Use last known location"),
        FieldSchema.Number("latitude", "Latitude", min = -90.0, max = 90.0),
        FieldSchema.Number("longitude", "Longitude", min = -180.0, max = 180.0),
    )

    private fun registerSolarTimes(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.solar.times"),
                FeatureKind.ACTION,
                "Get sunrise and sunset",
                "Calculate today's sunrise and sunset locally from latitude and longitude",
                FeatureCategory.SYSTEM,
                fields = locationFields() + listOf(
                    FieldSchema.Variable("resultVariable", "Store solar times", true),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("sunrise", "sunset", "solar", "dawn", "dusk", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val point = resolvePoint(feature.config.boolean("useLastKnown"), feature.config["latitude"].numberOrNull(), feature.config["longitude"].numberOrNull())
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.location_unavailable"))
            val date = LocalDate.now()
            val times = calculateSolarTimes(date, point.latitude, point.longitude)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.solar_times_unavailable"))
            val zone = ZoneId.systemDefault()
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "latitude" to ConfigValue.NumberValue(point.latitude),
                    "longitude" to ConfigValue.NumberValue(point.longitude),
                    "sunriseEpochMs" to ConfigValue.NumberValue(times.sunriseEpochMs.toDouble()),
                    "sunsetEpochMs" to ConfigValue.NumberValue(times.sunsetEpochMs.toDouble()),
                    "sunriseLocal" to ConfigValue.StringValue(formatLocalTime(times.sunriseEpochMs, zone)),
                    "sunsetLocal" to ConfigValue.StringValue(formatLocalTime(times.sunsetEpochMs, zone)),
                    "date" to ConfigValue.StringValue(date.toString()),
                    "timeZone" to ConfigValue.StringValue(zone.id),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDaylight(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId),
            kind,
            "Sun above horizon",
            "Check whether the current time is between today's calculated sunrise and sunset",
            FeatureCategory.SYSTEM,
            fields = locationFields() + FieldSchema.Toggle("value", "Daylight / sun up"),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("daylight", "sunrise", "sunset", "sun up", "solar", "macrodroid"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val point = resolvePoint(feature.config.boolean("useLastKnown"), feature.config["latitude"].numberOrNull(), feature.config["longitude"].numberOrNull())
                ?: return@ConditionEvaluator false
            val times = calculateSolarTimes(LocalDate.now(), point.latitude, point.longitude)
                ?: return@ConditionEvaluator false
            val now = System.currentTimeMillis()
            val daylight = now in times.sunriseEpochMs until times.sunsetEpochMs
            daylight == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerSolarEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sunrise_sunset"),
                FeatureKind.EVENT,
                "Sunrise or sunset",
                "Trigger on the calculated sunrise or sunset minute with an optional time offset",
                FeatureCategory.SYSTEM,
                fields = locationFields() + listOf(
                    FieldSchema.Choice("event", "Solar event", true, listOf("sunrise", "sunset")),
                    FieldSchema.Number("offsetMinutes", "Offset minutes", min = -720.0, max = 720.0),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("sunrise", "sunset", "solar", "offset", "macrodroid", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.time_tick") return@registerEvent false
            val point = resolvePoint(feature.config.boolean("useLastKnown"), feature.config["latitude"].numberOrNull(), feature.config["longitude"].numberOrNull())
                ?: return@registerEvent false
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val localDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val times = calculateSolarTimes(localDate, point.latitude, point.longitude) ?: return@registerEvent false
            val base = if (feature.config.string("event", "sunrise") == "sunset") times.sunsetEpochMs else times.sunriseEpochMs
            val offset = feature.config["offsetMinutes"].numberOrNull()?.takeIf { it.isFinite() && it in -720.0..720.0 } ?: 0.0
            val target = base + (offset * 60_000.0).toLong()
            sameLocalMinute(now, target, zone)
        }
    }

    private fun resolvePoint(useLastKnown: Boolean, latitude: Double?, longitude: Double?): SolarPoint? {
        if (!useLastKnown) {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            return if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) SolarPoint(lat, lon) else null
        }
        if (!hasLocationPermission()) return null
        val location = runCatching {
            locations.getProviders(true)
                .mapNotNull { provider -> runCatching { locations.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull() ?: return null
        return SolarPoint(location.latitude, location.longitude)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

internal data class SolarPoint(val latitude: Double, val longitude: Double)
internal data class SolarTimes(val sunriseEpochMs: Long, val sunsetEpochMs: Long)

internal fun calculateSolarTimes(date: LocalDate, latitude: Double, longitude: Double): SolarTimes? {
    if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    val sunriseHours = solarUtcHours(date, latitude, longitude, sunrise = true) ?: return null
    val sunsetHours = solarUtcHours(date, latitude, longitude, sunrise = false) ?: return null
    val midnightUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    var sunrise = midnightUtc + (sunriseHours * 3_600_000.0).toLong()
    var sunset = midnightUtc + (sunsetHours * 3_600_000.0).toLong()
    if (sunset <= sunrise) sunset += 86_400_000L
    return SolarTimes(sunrise, sunset)
}

private fun solarUtcHours(date: LocalDate, latitude: Double, longitude: Double, sunrise: Boolean): Double? {
    val day = date.dayOfYear.toDouble()
    val lngHour = longitude / 15.0
    val approximateTime = day + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
    val meanAnomaly = 0.9856 * approximateTime - 3.289
    var trueLongitude = meanAnomaly +
        1.916 * sin(Math.toRadians(meanAnomaly)) +
        0.020 * sin(Math.toRadians(2.0 * meanAnomaly)) +
        282.634
    trueLongitude = normalizeDegrees(trueLongitude)

    var rightAscension = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(trueLongitude))))
    rightAscension = normalizeDegrees(rightAscension)
    val longitudeQuadrant = floor(trueLongitude / 90.0) * 90.0
    val rightAscensionQuadrant = floor(rightAscension / 90.0) * 90.0
    rightAscension = (rightAscension + longitudeQuadrant - rightAscensionQuadrant) / 15.0

    val sinDeclination = 0.39782 * sin(Math.toRadians(trueLongitude))
    val cosDeclination = cos(asin(sinDeclination))
    val zenith = Math.toRadians(90.833)
    val cosHourAngle = (cos(zenith) - sinDeclination * sin(Math.toRadians(latitude))) /
        (cosDeclination * cos(Math.toRadians(latitude)))
    if (cosHourAngle > 1.0 || cosHourAngle < -1.0) return null

    var hourAngle = Math.toDegrees(acos(cosHourAngle))
    if (sunrise) hourAngle = 360.0 - hourAngle
    hourAngle /= 15.0

    val localMeanTime = hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
    return normalizeHours(localMeanTime - lngHour)
}

private fun normalizeDegrees(value: Double): Double {
    var result = value % 360.0
    if (result < 0.0) result += 360.0
    return result
}

private fun normalizeHours(value: Double): Double {
    var result = value % 24.0
    if (result < 0.0) result += 24.0
    return result
}

internal fun sameLocalMinute(aEpochMs: Long, bEpochMs: Long, zone: ZoneId): Boolean {
    val a = Instant.ofEpochMilli(aEpochMs).atZone(zone).withSecond(0).withNano(0)
    val b = Instant.ofEpochMilli(bEpochMs).atZone(zone).withSecond(0).withNano(0)
    return a == b
}

internal fun formatLocalTime(epochMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
