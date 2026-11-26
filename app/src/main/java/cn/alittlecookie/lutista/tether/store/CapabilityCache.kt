package cn.alittlecookie.lutista.tether.store

import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class CapabilityCacheKey(
    val model: String,
    val serial: String,
    val firmware: String,
    val probeSchema: Int = CapabilityCache.PROBE_SCHEMA,
)

/** 持久化类型仅有三项事实，类型层面不提供 writable、ModePos 或档位表字段。 */
data class CachedCapability(
    val availability: Availability,
    val evidence: CapabilityEvidence,
    val detail: String? = null,
)

data class CapabilityCacheEntry(
    val key: CapabilityCacheKey,
    val profileId: String,
    val probedAt: Long,
    val features: Map<Feature, CachedCapability>,
)

data class CapabilityCacheDocument(val schema: Int, val entries: List<CapabilityCacheEntry>)

/** 生产默认 org.json；JVM 可注入已有测试依赖支持的 codec，无需新增 main 依赖。 */
interface CapabilityCacheCodec {
    fun decode(json: String): CapabilityCacheDocument
    fun encode(document: CapabilityCacheDocument): String
}

/**
 * lambda 由组装层连接 SettingsManager.tetherCapsCache.first()/setTetherCapsCache。
 * save/invalidate 的异步 launch 由会话 scope 决定；本类不持有 scope，不阻止关闭。
 * 同一实例串行化读改写；LRU 最近使用在前，最多 4 个机身/固件/schema 组合。
 */
class CapabilityCache(
    private val read: suspend () -> String,
    private val write: suspend (String) -> Unit,
    private val log: (String) -> Unit = {},
    private val codec: CapabilityCacheCodec = OrgJsonCapabilityCacheCodec,
) {
    private val mutex = Mutex()

    suspend fun load(key: CapabilityCacheKey): CameraCapabilities? = mutex.withLock {
        val entries = readEntries()
        val index = if (key.probeSchema == PROBE_SCHEMA) entries.indexOfFirst { it.key == key } else -1
        if (index < 0) {
            emit("cache miss key=$key schema=$PROBE_SCHEMA lruCount=${entries.size}")
            return@withLock null
        }
        val entry = entries[index]
        if (index != 0) persist(listOf(entry) + entries.filterIndexed { i, _ -> i != index })
        emit("cache hit key=$key schema=$PROBE_SCHEMA lruCount=${entries.size}")
        CameraCapabilities(
            entry.key.model, entry.key.serial, entry.key.firmware, entry.profileId,
            entry.probedAt, 0L,
            entry.features.mapValues { (_, stored) ->
                // 历史 detail 原样显示；绝不据 count=xx 或 malicious writable=true 恢复可写。
                Capability(stored.availability, stored.evidence, stored.detail, writable = false)
            },
            probedModePos = null,
        )
    }

    suspend fun save(caps: CameraCapabilities) = mutex.withLock {
        val key = CapabilityCacheKey(caps.model, caps.serial, caps.firmware)
        val entry = CapabilityCacheEntry(
            key, caps.profileId, caps.probedAt,
            Feature.entries.associateWith { feature ->
                val value = caps[feature]
                CachedCapability(value.availability, value.evidence, value.detail)
            },
        )
        val entries = (listOf(entry) + readEntries().filterNot { it.key == key }).take(MAX_ENTRIES)
        persist(entries)
        emit("cache save key=$key schema=$PROBE_SCHEMA lruCount=${entries.size}")
    }

    suspend fun invalidate() = mutex.withLock {
        try {
            write("")
            emit("cache invalidate schema=$PROBE_SCHEMA lruCount=0")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit("cache invalidate failed schema=$PROBE_SCHEMA cause=${failure.javaClass.simpleName}")
        }
    }

    private suspend fun readEntries(): List<CapabilityCacheEntry> = try {
        val json = read()
        if (json.isBlank()) emptyList()
        else {
            val document = codec.decode(json)
            if (document.schema != PROBE_SCHEMA) emptyList()
            else document.entries.filter { it.key.probeSchema == PROBE_SCHEMA }.distinctBy { it.key }.take(MAX_ENTRIES)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        emit("cache read failed schema=$PROBE_SCHEMA cause=${failure.javaClass.simpleName}")
        emptyList()
    }

    private suspend fun persist(entries: List<CapabilityCacheEntry>) {
        try {
            write(codec.encode(CapabilityCacheDocument(PROBE_SCHEMA, entries.take(MAX_ENTRIES))))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            emit("cache write failed schema=$PROBE_SCHEMA cause=${failure.javaClass.simpleName}")
        }
    }

    private fun emit(message: String) { try { log(message) } catch (_: Exception) { /* 诊断失败不影响连接。 */ } }

    companion object {
        const val PROBE_SCHEMA = 1
        const val MAX_ENTRIES = 4
    }
}

/** 定稿 §11.9：main 使用 Android 已有 org.json，忽略未知字段但严格校验已知类型。 */
object OrgJsonCapabilityCacheCodec : CapabilityCacheCodec {
    override fun encode(document: CapabilityCacheDocument): String {
        val entries = JSONArray()
        for (entry in document.entries) {
            val features = JSONObject()
            for ((feature, value) in entry.features) {
                features.put(feature.name, JSONObject()
                    .put("availability", value.availability.name)
                    .put("evidence", value.evidence.name)
                    .put("detail", value.detail ?: JSONObject.NULL))
            }
            entries.put(JSONObject()
                .put("model", entry.key.model)
                .put("serial", entry.key.serial)
                .put("firmware", entry.key.firmware)
                .put("probeSchema", entry.key.probeSchema)
                .put("profileId", entry.profileId)
                .put("probedAt", entry.probedAt)
                .put("features", features))
        }
        return JSONObject().put("schema", document.schema).put("entries", entries).toString()
    }

    override fun decode(json: String): CapabilityCacheDocument {
        val root = JSONObject(json)
        val schema = integer(root, "schema")
        if (schema != CapabilityCache.PROBE_SCHEMA) return CapabilityCacheDocument(schema, emptyList())
        val rows = root.getJSONArray("entries")
        val entries = mutableListOf<CapabilityCacheEntry>()
        for (index in 0 until minOf(rows.length(), 64)) {
            val row = rows.getJSONObject(index)
            val features = row.getJSONObject("features")
            val values = linkedMapOf<Feature, CachedCapability>()
            for (feature in Feature.entries) {
                if (!features.has(feature.name)) continue
                val value = features.getJSONObject(feature.name)
                values[feature] = CachedCapability(
                    Availability.valueOf(text(value, "availability")),
                    CapabilityEvidence.valueOf(text(value, "evidence")),
                    if (!value.has("detail") || value.isNull("detail")) null else text(value, "detail"),
                )
            }
            entries += CapabilityCacheEntry(
                CapabilityCacheKey(text(row, "model"), text(row, "serial"), text(row, "firmware"), integer(row, "probeSchema")),
                text(row, "profileId"), long(row, "probedAt"), values,
            )
        }
        return CapabilityCacheDocument(schema, entries)
    }

    private fun text(json: JSONObject, name: String): String = json.get(name).let {
        require(it is String) { "Cache field $name must be text" }
        it
    }
    private fun long(json: JSONObject, name: String): Long = json.get(name).let {
        require(it is Int || it is Long) { "Cache field $name must be an integer" }
        (it as Number).toLong()
    }
    private fun integer(json: JSONObject, name: String): Int = long(json, name).let {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Cache field $name is out of range" }
        it.toInt()
    }
}
