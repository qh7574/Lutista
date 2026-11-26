package cn.alittlecookie.lutista.tether.store

import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import kotlinx.serialization.json.*

/** 仅 JVM 测试使用仓库已有 serialization-json；APK 默认实现始终为 org.json。 */
internal object JvmCacheJsonCodec : CapabilityCacheCodec {
    override fun encode(document: CapabilityCacheDocument): String = buildJsonObject {
        put("schema", document.schema)
        putJsonArray("entries") {
            for (entry in document.entries) add(buildJsonObject {
                put("model", entry.key.model)
                put("serial", entry.key.serial)
                put("firmware", entry.key.firmware)
                put("probeSchema", entry.key.probeSchema)
                put("profileId", entry.profileId)
                put("probedAt", entry.probedAt)
                putJsonObject("features") {
                    for ((feature, value) in entry.features) putJsonObject(feature.name) {
                        put("availability", value.availability.name)
                        put("evidence", value.evidence.name)
                        put("detail", value.detail?.let(::JsonPrimitive) ?: JsonNull)
                    }
                }
            })
        }
    }.toString()

    override fun decode(json: String): CapabilityCacheDocument {
        val root = Json.parseToJsonElement(json).jsonObject
        val schema = root.getValue("schema").jsonPrimitive.int
        if (schema != CapabilityCache.PROBE_SCHEMA) return CapabilityCacheDocument(schema, emptyList())
        return CapabilityCacheDocument(schema, root.getValue("entries").jsonArray.map { node ->
            val row = node.jsonObject
            val values = row.getValue("features").jsonObject
            CapabilityCacheEntry(
                CapabilityCacheKey(row.text("model"), row.text("serial"), row.text("firmware"), row.getValue("probeSchema").jsonPrimitive.int),
                row.text("profileId"), row.getValue("probedAt").jsonPrimitive.long,
                Feature.entries.filter { it.name in values }.associateWith { feature ->
                    val value = values.getValue(feature.name).jsonObject
                    CachedCapability(Availability.valueOf(value.text("availability")), CapabilityEvidence.valueOf(value.text("evidence")), value["detail"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                },
            )
        })
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
}
