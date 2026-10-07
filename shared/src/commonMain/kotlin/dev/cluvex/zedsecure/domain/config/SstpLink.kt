package dev.cluvex.zedsecure.domain.config

/**
 * The link form a subscription carries an SSTP relay in:
 * `sstp://user:pass@host:port?sni=example#Name`.
 *
 * That is the point of it: a server list can be published anywhere fetchable
 * and the app picks it up on refresh, instead of every new relay needing a
 * new app version.
 */
object SstpLink {
    private const val PREFIX = "sstp://"
    private const val HEX = "0123456789ABCDEF"

    fun isSstpLink(text: String): Boolean = text.trim().startsWith(PREFIX, ignoreCase = true)

    /** (display name, settings), or null when the link carries no usable server. */
    fun parse(text: String): Pair<String, SstpProfile>? {
        val trimmed = text.trim()
        if (!isSstpLink(trimmed)) return null

        val body = trimmed.substring(PREFIX.length).substringBefore('#')
        if (body.isBlank()) return null

        val withoutQuery = body.substringBefore('?')
        val query = body.substringAfter('?', "")

        val userInfo = withoutQuery.substringBeforeLast('@', "")
        val endpoint = withoutQuery.substringAfterLast('@', "")
        if (userInfo.isBlank() || endpoint.isBlank()) return null

        val host: String
        val port: Int
        if (endpoint.startsWith("[")) {
            host = endpoint.substringAfter('[').substringBefore(']')
            port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 443
        } else {
            host = endpoint.substringBeforeLast(':', endpoint)
            port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 443
        }
        if (host.isBlank()) return null

        val username = ConfigParser.percentDecode(userInfo.substringBefore(':', userInfo))
        val password = ConfigParser.percentDecode(userInfo.substringAfter(':', ""))
        if (username.isBlank()) return null

        val sni = query.split('&')
            .firstOrNull { it.startsWith("sni=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { ConfigParser.percentDecode(it) }
            .orEmpty()

        val name = ConfigParser.percentDecode(trimmed.substringAfter('#', "")).ifBlank { host }

        return name to SstpProfile(
            server = host,
            port = port,
            username = username,
            password = password,
            sni = sni,
        )
    }

    /** Canonical body with no name, so two links to the same relay compare equal. */
    fun build(settings: SstpProfile): String = buildString {
        append(PREFIX)
        append(encode(settings.username))
        append(':')
        append(encode(settings.password))
        append('@')
        if (settings.server.contains(':') && !settings.server.startsWith("[")) {
            append('[').append(settings.server).append(']')
        } else {
            append(settings.server)
        }
        append(':').append(settings.port)
        if (settings.sni.isNotBlank()) append("?sni=").append(encode(settings.sni))
    }

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (b >= 0x80) {
                // Multi-byte UTF-8: emit its own bytes, each percent-escaped.
                append('%').append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
            } else if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~") {
                append(c)
            } else {
                append('%').append(HEX[(b shr 4) and 0xF]).append(HEX[b and 0xF])
            }
        }
    }
}
