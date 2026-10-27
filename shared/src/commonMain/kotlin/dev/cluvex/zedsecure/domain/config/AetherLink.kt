package dev.cluvex.zedsecure.domain.config

/**
 * The `aether://` link form PattNG publishes for Aether profiles, as seen
 * on the PattNG channel:
 *
 * `aether://?protocol=mim&scan=balanced&ip=both&transport=h2&outer=162.159.198.79%3A443&inner=162.159.198.106%3A443&psiphon=chain&psiphon_mode=auto#Argo_VPN1`
 * `aether://162.159.199.47:443?protocol=masque&scan=balanced&ip=v4&transport=h2&psiphon=chain&psiphon_mode=auto#Argo_VPN1 (2)`
 *
 * Every parameter maps to a flag of the stock Aether core (v2.4.0), the
 * same core this app ships, and the session arguments built here mirror
 * PattNG's session arguments: the link's own protocol, scan mode, IP
 * version, transport and endpoints, `--quick-reconnect`, and, for a
 * chained link, the Psiphon chain (`--psiphon`, `--psiphon-mode`,
 * `--psiphon-server-entries shipped-list`). The Psiphon listen port is
 * deliberately NOT fixed here: the service owns ports and binds the
 * tunnel to 1820 for chained profiles, leaving 1819 for the Psiphon
 * listener the app dials.
 *
 * A session never scans: the service strips `--scan` at connect time,
 * exactly as it does for probed profiles. A link carries its endpoint
 * explicitly (the `outer`/`inner` hops, or the authority), so nothing
 * is lost. A link with no endpoint at all would connect with
 * `--quick-reconnect` and no peer, which only reuses a previous
 * connection from the shared work dir.
 */
object AetherLink {

    private const val PREFIX = "aether://"

    /** A parsed link: the name from its fragment and the profile to save. */
    data class Parsed(val name: String, val profile: AetherProfile)

    fun isAetherLink(text: String): Boolean =
        text.trim().startsWith(PREFIX, ignoreCase = true)

    /**
     * Parses one link. Throws [IllegalArgumentException] when the link is
     * not an `aether://` link, names an unsupported protocol, or a MIM
     * link carries no outer hop.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        require(isAetherLink(trimmed)) { "not an aether:// link" }
        val rest = trimmed.substring(PREFIX.length)
        val fragment = rest.substringAfter('#', "")
        val beforeFragment = rest.substringBefore('#')
        val authority = beforeFragment.substringBefore('?')
        val query = beforeFragment.substringAfter('?', "")
        val params = parseQuery(query)

        val protocol = params["protocol"]?.lowercase().orEmpty()
        require(protocol.isEmpty() || protocol == "masque" || protocol == "mim") {
            "unsupported Aether protocol: $protocol"
        }
        val effectiveProtocol = protocol.ifEmpty { "masque" }
        val scan = params["scan"]?.takeIf { it.isNotBlank() } ?: "balanced"
        val ip = when (params["ip"]?.lowercase()) {
            "v4" -> "v4"
            "v6" -> "v6"
            else -> "both"
        }
        val name = decode(fragment).trim().ifEmpty { "Aether" }

        var address = ""
        val args = buildList {
            add("--protocol"); add(effectiveProtocol)
            add("--scan"); add(scan)
            add("--ip"); add(ip)
            if (params["transport"]?.lowercase() == "h2") add("--h2")
            params["sni"]?.takeIf { it.isNotBlank() && effectiveProtocol == "masque" }?.let {
                add("--masque-sni"); add(it)
            }
            if (effectiveProtocol == "mim") {
                val outer = params["outer"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("mim link needs an outer endpoint")
                add("--mim-outer"); add(outer)
                address = hostOf(outer)
                params["inner"]?.takeIf { it.isNotBlank() }?.let {
                    add("--mim-inner"); add(it)
                }
            } else {
                val peer = decode(authority).trim()
                if (peer.isNotEmpty()) {
                    add("--peer"); add(peer)
                    address = hostOf(peer)
                }
            }
            add("--quick-reconnect")
            if (params["psiphon"]?.lowercase() == "chain") {
                add("--psiphon")
                add("--psiphon-mode")
                add(params["psiphon_mode"]?.takeIf { it.isNotBlank() } ?: "auto")
                add("--psiphon-server-entries"); add("shipped-list")
            }
        }
        return Parsed(
            name = name,
            profile = AetherProfile(
                method = "link",
                args = args,
                displayName = name,
                address = address,
                ipVersion = if (ip == "both") "any" else ip,
            ),
        )
    }

    /** Host part of a `host:port` endpoint, brackets stripped for IPv6. */
    private fun hostOf(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.startsWith("[")) return trimmed.substringAfter('[').substringBefore(']')
        return trimmed.substringBefore(':')
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val params = linkedMapOf<String, String>()
        query.split('&').forEach { part ->
            if (part.isEmpty()) return@forEach
            val eq = part.indexOf('=')
            val key = decode(if (eq >= 0) part.substring(0, eq) else part).lowercase().trim()
            val value = if (eq >= 0) decode(part.substring(eq + 1)) else ""
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    /** Percent-decodes UTF-8, leaving malformed `%` sequences untouched. */
    private fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length && value[i + 1].isHexDigit() && value[i + 2].isHexDigit()) {
                bytes += value.substring(i + 1, i + 3).toInt(16).toByte()
                i += 3
            } else {
                c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
