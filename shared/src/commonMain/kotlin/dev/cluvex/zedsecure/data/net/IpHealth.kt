package dev.cluvex.zedsecure.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The IP-hygiene report Hossein asked for: how the exit address is seen by
 * risk databases — score, what it is flagged for, where it really is, and
 * which platforms it is allowed on.
 *
 * Source: ipok.io (free, no key, CORS-enabled). One request returns all of it.
 */
data class IpHealth(
    val risk: Int? = null,
    val band: String? = null,
    val bandTone: String? = null,
    val ipType: String? = null,
    val signals: List<String> = emptyList(),
    val country: String? = null,
    val city: String? = null,
    val timezone: String? = null,
    val asn: String? = null,
    val asnName: String? = null,
    val isp: String? = null,
    val org: String? = null,
    val reverse: String? = null,
    val blocklisted: Int? = null,
    val blocklistChecked: Int? = null,
    val services: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasData: Boolean get() = risk != null || country != null
    val blocklistClean: Boolean
        get() = (blocklistChecked ?: 0) > 0 && (blocklisted ?: 0) == 0
}

object IpHealthPayload {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String): IpHealth {
        val root = json.parseToJsonElement(body).jsonObject

        fun JsonObject?.text(key: String): String? =
            runCatching { this?.get(key)?.jsonPrimitive?.content }.getOrNull()
                ?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

        fun JsonObject?.int(key: String): Int? =
            runCatching { this?.get(key)?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull()

        fun JsonObject?.strings(key: String): List<String> =
            runCatching {
                (this?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.content }
            }.getOrNull().orEmpty()

        val geo = runCatching { root["geo"]?.jsonObject }.getOrNull()
        val band = runCatching { root["purityBand"]?.jsonObject }.getOrNull()
        val block = runCatching { root["blocklist"]?.jsonObject }.getOrNull()

        val serviceNames = runCatching {
            (root["services"] as? JsonArray)?.mapNotNull { el ->
                val o = el.jsonObject
                val name = o.text("name")
                val status = o.text("status")
                if (name != null && status != null) "$name $status" else null
            }
        }.getOrNull().orEmpty()

        return IpHealth(
            risk = root.int("risk"),
            band = band.text("label"),
            bandTone = band.text("tone"),
            ipType = root.text("ipType"),
            signals = root.strings("signals"),
            country = geo.text("country"),
            city = geo.text("city"),
            timezone = geo.text("timezone"),
            asn = geo.text("asn"),
            asnName = geo.text("asName"),
            isp = geo.text("isp"),
            org = geo.text("org"),
            reverse = geo.text("reverse"),
            blocklisted = runCatching { block?.get("listed") as? JsonArray }.getOrNull()?.size,
            blocklistChecked = block.int("checked"),
            services = serviceNames,
        )
    }
}
