package dev.cluvex.zedsecure.domain.config

/**
 * Normalization and validation of `host:port` peer endpoints for the
 * Aether core (v2.4.0).
 *
 * The core's `--peer` parser rejects an unbracketed IPv6 literal:
 * `--peer 2606:4700:103::ac41:fb71:443` dies at startup with
 * `bad peer address ...` (seen on device 2026-10-29: the auto-config
 * probe logged the gateway unbracketed, the app pinned it verbatim,
 * and every connect with it exited with code 1 before the SOCKS port
 * ever answered). IPv6 literals in `host:port` form need brackets:
 * `[2606:4700:103::ac41:fb71]:443`.
 *
 * Both layers must use this helper: parse time (so no new bad value is
 * stored) AND emit time in the service (so profiles saved before this
 * fix, which already carry the bad unbracketed value, connect without
 * being deleted or recreated).
 */
object AetherPeer {

    /**
     * Brackets an unbracketed IPv6 `host:port` value. IPv4, hostnames,
     * already-bracketed values, bare hosts and anything that does not
     * look like an IPv6 literal with a numeric port pass through
     * untouched.
     */
    fun normalize(peer: String): String {
        val s = peer.trim()
        if (s.isEmpty() || s.startsWith("[")) return s
        if ('%' in s) return s // zone id — leave alone, never seen as a peer
        val colonCount = s.count { it == ':' }
        if (colonCount < 2) return s
        val lastColon = s.lastIndexOf(':')
        val host = s.substring(0, lastColon)
        val port = s.substring(lastColon + 1)
        if (host.isEmpty() || host.endsWith(':')) return s
        if (port.isEmpty() || !port.all { it.isDigit() }) return s
        return "[$host]:$port"
    }

    /**
     * True when [peer] is a usable endpoint for the core: `host:port`
     * with a numeric port in range, where an unbracketed host must not
     * itself contain a colon. Mirrors the editor's
     * `AetherManualSettings.isValidEndpoint` rule, applied AFTER
     * [normalize], so a bracketed IPv6 peer counts as usable.
     */
    fun isUsable(peer: String): Boolean {
        val s = normalize(peer)
        if (s.isEmpty()) return false
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close <= 1) return false
            val rest = s.substring(close + 1)
            if (!rest.startsWith(":")) return false
            val port = rest.drop(1)
            return port.isNotEmpty() && port.all { it.isDigit() } &&
                (port.toIntOrNull() in 1..65535)
        }
        val idx = s.lastIndexOf(':')
        if (idx <= 0) return false
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1)
        if (host.isEmpty() || host.contains(':')) return false
        return port.isNotEmpty() && port.all { it.isDigit() } &&
            (port.toIntOrNull() in 1..65535)
    }
}
