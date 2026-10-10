package com.yagay.yauto.core.importer

enum class SourceFeatureKind { EVENT, STATE, CONDITION, ACTION }

fun interface SourceFeatureMapper {
    fun targetId(sourceType: String, kind: SourceFeatureKind): String?
}

class MapSourceFeatureMapper(
    private val mappings: Map<Pair<SourceFeatureKind, String>, String>,
) : SourceFeatureMapper {
    override fun targetId(sourceType: String, kind: SourceFeatureKind): String? = mappings[kind to sourceType]

    fun validateTargets(validNativeIds: Set<String>): List<String> = mappings.entries
        .filter { (_, nativeId) -> nativeId !in validNativeIds }
        .map { (source, nativeId) -> "${source.first}:${source.second} -> $nativeId" }
        .sorted()
}

object EmptySourceFeatureMapper : SourceFeatureMapper {
    override fun targetId(sourceType: String, kind: SourceFeatureKind): String? = null
}
