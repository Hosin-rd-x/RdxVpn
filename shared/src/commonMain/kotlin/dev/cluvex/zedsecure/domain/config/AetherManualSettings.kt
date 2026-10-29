package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.Serializable

/**
 * Structured Aether settings for the manual editor: the same setting
 * model PattNG's editor holds (protocol, scan, transport, IP version,
 * obfuscation, endpoints, MASQUE/ECH/TLS shaping, Psiphon, advanced
 * timing knobs), expressed as the stock Aether v2.4.0 CLI flags.
 *
 * This is RdxVpn's own mapping, written from the core's own CLI usage
 * text (aether/src/cli.rs). It is NOT a copy of PattNG's builder: the
 * TLS fingerprint presets below only use values the core's help text
 * itself documents (its Chrome defaults and its worked examples). The
 * long opaque ClientHello lists were deliberately NOT reproduced: the
 * "chrome" preset emits nothing at all, because the core's default
 * handshake already is Chrome's, and the other presets only change the
 * documented toggles and list orders. A fingerprint that always starts
 * beats an exact one that can fail to parse.
 *
 * Only [args] are stored on the profile ([AetherProfile.args]); this
 * object is transient editor state. [toArgs] builds the stored form
 * (scan form: the service strips --scan at connect time and pins the
 * gateway, exactly like the automatic and link profiles). [fromArgs]
 * parses stored args back so an existing profile can be edited; flags
 * it does not know are kept verbatim as [extraArgs] text so editing
 * never silently drops a setting, and service-owned flags (--bind,
 * --psiphon-bind, --log-level, config paths, Tor, account flags) are
 * dropped because the service owns them.
 */
@Serializable
data class AetherManualSettings(
    val protocol: String = PROTOCOL_MASQUE,
    val scanMode: String = SCAN_BALANCED,
    val transport: String = TRANSPORT_H2,
    val ipVersion: String = IP_BOTH,
    val noize: String = NOIZE_AUTO,
    /** Single-hop endpoint, host:port. Empty means "scan / reuse last". */
    val peer: String = "",
    /** Two-hop outer endpoint, host:port. */
    val outerPeer: String = "",
    /** Two-hop inner endpoint, host:port. */
    val innerPeer: String = "",
    val masqueSni: String = "",
    val dns: String = "",
    val exitLoc: String = "",
    val fragment: Boolean = false,
    val fragmentSize: String = "",
    val fragmentDelay: String = "",
    val ech: Boolean = false,
    val echDns: String = "",
    val echDomain: String = "",
    /** TLS fingerprint preset: chrome, chrome-120, firefox, semi-python, go. */
    val fingerprint: String = FINGERPRINT_CHROME,
    val psiphon: String = PSIPHON_OFF,
    val psiphonMode: String = PSIPHON_MODE_AUTO,
    val psiphonRegion: String = "",
    val psiphonCdnIps: String = "",
    val psiphonCdnSni: String = "",
    /** Comma-separated CDN set names, in canonical order. */
    val psiphonCdnSets: String = "",
    val psiphonBundledList: Boolean = true,
    val reconnectSecs: String = "",
    val keepaliveSecs: String = "",
    val validateSecs: String = "",
    val startupSecs: String = "",
    /** "" = auto-detect; low, medium, high. */
    val perf: String = "",
    val noQuicV2: Boolean = false,
    val h2Peer: String = "",
    /** Free-form extra arguments, shell-split, appended last. */
    val extraArgs: String = "",
    val routeBlock: String = "",
    val routeDirect: String = "",
    val upstream: String = "",
    /** True when [fromArgs] met flags it does not model (kept in [extraArgs]). */
    val hasUnknownArgs: Boolean = false,
    /** Service-owned flags dropped while parsing stored args; shown in the editor. */
    val droppedFlags: List<String> = emptyList(),
) {
    val isTwoHop: Boolean get() = protocol == PROTOCOL_GOOL || protocol == PROTOCOL_MIM ||
        protocol == PROTOCOL_WG_OVER_MASQUE

    val isOverMasque: Boolean get() = protocol == PROTOCOL_MASQUE || protocol == PROTOCOL_MIM ||
        protocol == PROTOCOL_WG_OVER_MASQUE

    /** Stored argument list, scan form. Never includes --bind or --quick-reconnect. */
    fun toArgs(): List<String> = buildList {
        // Protocol: the core word for wg-over-masque is "gool" (the
        // gool of 2.3.0+); classic gool adds --gool-classic.
        when (protocol) {
            PROTOCOL_MASQUE -> addAll(listOf("--protocol", "masque"))
            PROTOCOL_WG -> addAll(listOf("--protocol", "wg"))
            PROTOCOL_GOOL -> {
                addAll(listOf("--protocol", "gool"))
                add("--gool-classic")
            }
            PROTOCOL_MIM -> addAll(listOf("--protocol", "mim"))
            PROTOCOL_WG_OVER_MASQUE -> addAll(listOf("--protocol", "gool"))
        }
        addAll(listOf("--scan", scanMode))
        // Obfuscation shapes the UDP of WireGuard and HTTP/3 alone, so
        // MASQUE over HTTP/2 takes none; "auto" is the core's own choice.
        val masqueOverH2 = isOverMasque && transport == TRANSPORT_H2 &&
            psiphon != PSIPHON_REVERSE && psiphon != PSIPHON_ONLY
        if (noize != NOIZE_AUTO && !masqueOverH2) {
            addAll(listOf("--noize", noize))
        }
        addAll(listOf("--ip", ipVersion))
        settingValue(dns)?.let { addAll(listOf("--dns", it)) }
        settingValue(exitLoc)?.let { addAll(listOf("--exit-loc", it)) }
        if (isOverMasque) {
            settingValue(masqueSni)?.let { addAll(listOf("--masque-sni", it)) }
        }
        if (isOverMasque) {
            // The transport is always written explicitly: H2 as --h2, H3
            // as --h3. A missing flag reads back as H2 (see fromArgs), so
            // leaving H3 silent would corrupt it to H2 on re-save.
            if (transport == TRANSPORT_H2) {
                add("--h2")
                if (fragment) {
                    add("--fragment")
                    settingValue(fragmentSize)?.let { addAll(listOf("--fragment-size", it)) }
                    settingValue(fragmentDelay)?.let { addAll(listOf("--fragment-delay", it)) }
                }
            } else {
                add("--h3")
            }
        }
        if (isOverMasque && ech) {
            addAll(listOf("--ech", "auto"))
            settingValue(echDns)?.let { addAll(listOf("--ech-dns", it)) }
            settingValue(echDomain)?.let { addAll(listOf("--ech-domain", it)) }
        }
        if (isOverMasque) addAll(fingerprintArgs(fingerprint))
        when (protocol) {
            PROTOCOL_WG_OVER_MASQUE -> {
                // Modern gool: the outer hop is the MASQUE peer, the
                // inner hop the WireGuard endpoint dialled inside it.
                settingValue(outerPeer)?.let { addAll(listOf("--peer", it)) }
                settingValue(innerPeer)?.let { addAll(listOf("--gool-peer", it)) }
                if (outerPeer.isBlank() && innerPeer.isBlank()) add("--wiw-scan")
            }
            PROTOCOL_GOOL -> {
                // Classic gool (WireGuard in WireGuard) shares the same
                // endpoint pair; only --gool-classic above differs.
                settingValue(outerPeer)?.let { addAll(listOf("--peer", it)) }
                settingValue(innerPeer)?.let { addAll(listOf("--gool-peer", it)) }
            }
            PROTOCOL_MIM -> {
                settingValue(outerPeer)?.let { addAll(listOf("--mim-outer", it)) }
                settingValue(innerPeer)?.let { addAll(listOf("--mim-inner", it)) }
                if (outerPeer.isBlank() && innerPeer.isBlank()) add("--mim-scan")
            }
            else -> {
                settingValue(peer)?.let { addAll(listOf("--peer", it)) }
            }
        }
        when (psiphon) {
            PSIPHON_CHAIN -> add("--psiphon")
            PSIPHON_REVERSE -> add("--psiphon-reverse")
            PSIPHON_ONLY -> add("--psiphon-only")
        }
        if (psiphon != PSIPHON_OFF) {
            addAll(listOf("--psiphon-mode", psiphonMode))
            if (psiphonMode != PSIPHON_MODE_DIRECT) {
                settingValue(psiphonCdnIps)?.let { addAll(listOf("--psiphon-cdn-ips", it)) }
                if (psiphonCdnIps.isNotBlank()) {
                    settingValue(psiphonCdnSni)?.let { addAll(listOf("--psiphon-cdn-sni", it)) }
                }
                canonicalCdnSets(psiphonCdnSets)?.let { addAll(listOf("--psiphon-cdn-sets", it)) }
            }
            settingValue(psiphonRegion)?.let { addAll(listOf("--psiphon-region", it)) }
            if (psiphonBundledList) {
                addAll(listOf("--psiphon-server-entries", "shipped-list"))
            }
        }
        settingValue(reconnectSecs)?.let { addAll(listOf("--reconnect-secs", it)) }
        settingValue(keepaliveSecs)?.let { addAll(listOf("--keepalive", it)) }
        settingValue(validateSecs)?.let { addAll(listOf("--validate-secs", it)) }
        settingValue(startupSecs)?.let { addAll(listOf("--startup-secs", it)) }
        settingValue(perf)?.let { addAll(listOf("--perf", it)) }
        if (noQuicV2) add("--no-quic-v2")
        settingValue(h2Peer)?.let { addAll(listOf("--h2-peer", it)) }
        settingValue(routeBlock)?.let { addAll(listOf("--route-block", it)) }
        settingValue(routeDirect)?.let { addAll(listOf("--route-direct", it)) }
        settingValue(upstream)?.let { addAll(listOf("--upstream", it)) }
        addAll(filterExtraArgs(splitExtraArgs(extraArgs)))
    }

    /**
     * Flag names these settings would silently drop on save, for the
     * editor's hint line: blocked flags in the extra-args text, values
     * that read as their own flag, and settings the current protocol /
     * transport / Psiphon shape does not emit. Noize on H2 is excluded:
     * the editor disables that selector with its own note instead.
     */
    fun droppedOnSave(): List<String> = buildList {
        addAll(droppedExtraArgs(splitExtraArgs(extraArgs)))
        // A structured value that looks like its own flag is rejected
        // by settingValue and never reaches the core.
        fun rejected(flag: String, value: String) {
            if (value.isNotBlank() && settingValue(value) == null) add(flag)
        }
        rejected("--dns", dns)
        rejected("--exit-loc", exitLoc)
        rejected("--masque-sni", masqueSni)
        rejected("--fragment-size", fragmentSize)
        rejected("--fragment-delay", fragmentDelay)
        rejected("--ech-dns", echDns)
        rejected("--ech-domain", echDomain)
        rejected("--peer", peer)
        rejected("--mim-outer", outerPeer)
        rejected("--mim-inner", innerPeer)
        rejected("--psiphon-cdn-ips", psiphonCdnIps)
        rejected("--psiphon-cdn-sni", psiphonCdnSni)
        rejected("--psiphon-region", psiphonRegion)
        rejected("--reconnect-secs", reconnectSecs)
        rejected("--keepalive", keepaliveSecs)
        rejected("--validate-secs", validateSecs)
        rejected("--startup-secs", startupSecs)
        rejected("--perf", perf)
        rejected("--h2-peer", h2Peer)
        rejected("--route-block", routeBlock)
        rejected("--route-direct", routeDirect)
        rejected("--upstream", upstream)
        if (!isOverMasque) {
            if (masqueSni.isNotBlank()) add("--masque-sni")
            if (fragment) add("--fragment")
            if (ech) add("--ech")
            fingerprintArgs(fingerprint).firstOrNull()?.let { add(it) }
        } else if (transport != TRANSPORT_H2 && fragment) {
            // Fragment is H2-only; on H3 the switch is hidden, but a
            // stored profile can still carry it.
            add("--fragment")
        }
        if (psiphon == PSIPHON_OFF) {
            if (psiphonMode != PSIPHON_MODE_AUTO) add("--psiphon-mode")
            if (psiphonRegion.isNotBlank()) add("--psiphon-region")
            if (psiphonCdnIps.isNotBlank()) add("--psiphon-cdn-ips")
            if (psiphonCdnSni.isNotBlank()) add("--psiphon-cdn-sni")
            if (psiphonCdnSets.isNotBlank()) add("--psiphon-cdn-sets")
        } else if (psiphonMode == PSIPHON_MODE_DIRECT) {
            if (psiphonCdnIps.isNotBlank()) add("--psiphon-cdn-ips")
            if (psiphonCdnSni.isNotBlank()) add("--psiphon-cdn-sni")
            if (psiphonCdnSets.isNotBlank()) add("--psiphon-cdn-sets")
        } else if (psiphonCdnIps.isBlank() && psiphonCdnSni.isNotBlank()) {
            add("--psiphon-cdn-sni")
        }
        if (psiphonCdnSets.isNotBlank() && canonicalCdnSets(psiphonCdnSets) == null) {
            add("--psiphon-cdn-sets")
        }
    }.distinct()

    companion object {
        const val PROTOCOL_MASQUE = "masque"
        const val PROTOCOL_WG = "wg"
        const val PROTOCOL_GOOL = "gool"
        const val PROTOCOL_MIM = "mim"
        const val PROTOCOL_WG_OVER_MASQUE = "wg-over-masque"

        const val SCAN_TURBO = "turbo"
        const val SCAN_BALANCED = "balanced"
        const val SCAN_THOROUGH = "thorough"
        const val SCAN_VERIFIED = "verified"
        const val SCAN_IRONCLAD = "ironclad"

        const val TRANSPORT_H3 = "h3"
        const val TRANSPORT_H2 = "h2"

        const val IP_V4 = "v4"
        const val IP_V6 = "v6"
        const val IP_BOTH = "both"

        const val NOIZE_AUTO = "auto"
        const val NOIZE_OFF = "off"
        const val NOIZE_LIGHT = "light"
        const val NOIZE_FIREWALL = "firewall"
        const val NOIZE_BALANCED = "balanced"
        const val NOIZE_GFW = "gfw"
        const val NOIZE_AGGRESSIVE = "aggressive"

        const val FINGERPRINT_CHROME = "chrome"
        const val FINGERPRINT_CHROME_120 = "chrome-120"
        const val FINGERPRINT_FIREFOX = "firefox"
        const val FINGERPRINT_SEMI_PYTHON = "semi-python"
        const val FINGERPRINT_GO = "go"

        const val PSIPHON_OFF = "off"
        const val PSIPHON_CHAIN = "chain"
        const val PSIPHON_REVERSE = "reverse"
        const val PSIPHON_ONLY = "only"

        const val PSIPHON_MODE_AUTO = "auto"
        const val PSIPHON_MODE_CDN = "cdn"
        const val PSIPHON_MODE_DIRECT = "direct"

        /** CDN set names in the core's canonical order. */
        val CDN_SET_ORDER = listOf(
            "cloudflare", "fastly", "cloudfront", "psiphon-akamai",
            "psiphon-bunny", "vercel", "github", "curated-fronting",
            "legacy-android-overrides",
        )

        /**
         * Flags the service owns, or that would make the core exit at
         * once, or that belong to Tor / Zero Trust: never user-editable,
         * dropped from free-form extra args and from parsed profiles.
         */
        private val BLOCKED_VALUE_FLAGS = setOf(
            "--bind", "--psiphon-bind", "--tor-bind", "--log-level",
            "--config", "--wg-config", "--masque-config",
            "--psiphon-bin", "--psiphon-dir", "--tor-dir",
            "--tor-bridge-file", "--psiphon-config",
            "--register", "--team", "--access-id", "--access-secret",
            "--access-token", "--access-email",
            "--api-address", "--api-sni", "--api-host",
            "--stats", "--stats-secs", "--mark", "--http-proxy",
            "--tor-pt", "--tor-pt-dir", "--tor-relays", "--tor-relay-ports",
            "--tor-http", "--psiphon-http", "--tor-bridge",
        )
        private val BLOCKED_BOOL_FLAGS = setOf(
            "--tor", "--tor-reverse", "--tor-only",
            "--tor-bridges", "--no-tor-bridges",
            "--gateway", "--verbose", "--version", "--help", "-v", "-h",
        )

        /** Session markers the service manages: dropped on parse, re-added at connect. */
        private val SESSION_FLAGS = setOf(
            "--quick-reconnect", "--no-quick-reconnect",
        )

        /** Trimmed, null when blank or when it would read as its own flag. */
        fun settingValue(value: String?): String? =
            value?.trim()?.takeUnless { it.isEmpty() || it.startsWith('-') }

        /** True for host:port, with [ipv6]:port allowed; bare hosts are not endpoints. */
        fun isValidEndpoint(value: String): Boolean {
            val s = value.trim()
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

        /** Host part of a host:port endpoint, brackets stripped for IPv6. */
        fun hostOf(endpoint: String): String {
            val trimmed = endpoint.trim()
            if (trimmed.startsWith("[")) return trimmed.substringAfter('[').substringBefore(']')
            return trimmed.substringBefore(':')
        }

        /** CDN set names from free text, canonical order, unknown names dropped. */
        fun canonicalCdnSets(text: String): String? {
            val named = text.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.toSet()
            return CDN_SET_ORDER.filter { it in named }
                .joinToString(",").ifEmpty { null }
        }

        /**
         * Shell-like split: whitespace separates, single/double quotes
         * group, backslash escapes the next character.
         */
        fun splitExtraArgs(text: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            var i = 0
            fun flush() {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.clear()
                }
            }
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != null -> {
                        if (c == '\\' && i + 1 < text.length &&
                            (text[i + 1] == quote || text[i + 1] == '\\')
                        ) {
                            cur.append(text[i + 1])
                            i += 2
                        } else if (c == quote) {
                            quote = null
                            i++
                        } else {
                            cur.append(c)
                            i++
                        }
                    }
                    c == '\'' || c == '"' -> {
                        quote = c
                        i++
                    }
                    c == '\\' && i + 1 < text.length -> {
                        cur.append(text[i + 1])
                        i += 2
                    }
                    c.isWhitespace() -> {
                        flush()
                        i++
                    }
                    else -> {
                        cur.append(c)
                        i++
                    }
                }
            }
            flush()
            return out
        }

        /** Quote a token so [splitExtraArgs] reads it back exactly. */
        fun quoteExtraArg(token: String): String =
            if (token.any { it.isWhitespace() || it == '\'' || it == '"' || it == '\\' }) {
                "'" + token.replace("'", "'\\''") + "'"
            } else {
                token
            }

        /**
         * Drops blocked flags (with their values) and session markers
         * from free-form tokens. Unknown flags pass through: the extra-
         * args field is the user's own explicit choice, and the core
         * reports a bad flag itself.
         */
        fun filterExtraArgs(tokens: List<String>): List<String> =
            filterExtraArgsWithDropped(tokens).first

        /** Flag names [filterExtraArgs] drops from free-form tokens. */
        fun droppedExtraArgs(tokens: List<String>): List<String> =
            filterExtraArgsWithDropped(tokens).second

        private fun filterExtraArgsWithDropped(
            tokens: List<String>,
        ): Pair<List<String>, List<String>> {
            val out = mutableListOf<String>()
            val dropped = mutableListOf<String>()
            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                val flag = token.substringBefore('=')
                if (flag in BLOCKED_BOOL_FLAGS || flag in SESSION_FLAGS) {
                    dropped += flag
                    i += 1
                } else if (flag in BLOCKED_VALUE_FLAGS) {
                    dropped += flag
                    i += if ('=' in token) 1 else 2
                } else {
                    out += token
                    i += 1
                }
            }
            return out to dropped
        }

        /**
         * TLS flag bundle for a fingerprint preset. Only values the
         * core's own --help text documents are used; "chrome" emits
         * nothing because the core default already is Chrome's. See the
         * class doc for why the long ClientHello lists are not here.
         */
        fun fingerprintArgs(preset: String): List<String> = when (preset) {
            FINGERPRINT_CHROME_120 -> listOf(
                "--tls-groups", "X25519:P-256:P-384",
                "--tls-alps", "old",
                "--tls-cert-compression", "brotli",
                "--enable-ech-grease",
            )
            FINGERPRINT_FIREFOX -> listOf(
                "--disable-grease",
                "--tls13-ciphers",
                "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_256_GCM_SHA384",
                "--tls-cert-compression", "zlib:brotli:zstd",
                "--enable-ech-grease",
                "--disable-session-ticket",
                "--disable-extension-permutation",
            )
            FINGERPRINT_SEMI_PYTHON -> listOf(
                "--disable-grease",
                "--tls13-ciphers",
                "TLS_AES_256_GCM_SHA384:TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256",
                "--disable-ocsp-stapling",
                "--disable-sct",
                "--disable-extension-permutation",
            )
            FINGERPRINT_GO -> listOf(
                "--disable-grease",
                "--disable-session-ticket",
                "--disable-extension-permutation",
            )
            else -> emptyList()
        }

        /** Parses stored args back into editor settings. */
        fun fromArgs(args: List<String>): AetherManualSettings {
            var protocol = PROTOCOL_MASQUE
            var goolClassic = false
            var scanMode = SCAN_BALANCED
            var transport = TRANSPORT_H3
            var transportSeen = false
            var ipVersion = IP_BOTH
            var noize = NOIZE_AUTO
            var peer = ""
            var outerPeer = ""
            var innerPeer = ""
            var masqueSni = ""
            var dns = ""
            var exitLoc = ""
            var fragment = false
            var fragmentSize = ""
            var fragmentDelay = ""
            var ech = false
            var echDns = ""
            var echDomain = ""
            var psiphon = PSIPHON_OFF
            var psiphonMode = PSIPHON_MODE_AUTO
            var psiphonRegion = ""
            var psiphonCdnIps = ""
            var psiphonCdnSni = ""
            var psiphonCdnSets = ""
            var psiphonBundledList = false
            var reconnectSecs = ""
            var keepaliveSecs = ""
            var validateSecs = ""
            var startupSecs = ""
            var perf = ""
            var noQuicV2 = false
            var h2Peer = ""
            var routeBlock = ""
            var routeDirect = ""
            var upstream = ""
            // Fingerprint signature pieces, decided after the loop.
            // (Kept only as raw tokens; see consumedTls below.)
            val unknown = mutableListOf<String>()
            var hadUnknown = false
            // Service-owned flags dropped on parse, for the editor hint.
            val dropped = mutableListOf<String>()
            // TLS signature pieces, kept as raw tokens so a set that is
            // not exactly one of the preset bundles can be preserved
            // verbatim instead of silently dropped on re-save.
            val consumedTls = mutableListOf<String>()

            fun valueAt(i: Int): String? = args.getOrNull(i + 1)
            var i = 0
            while (i < args.size) {
                val token = args[i]
                val flag = token.substringBefore('=')
                val inlineValue = if ('=' in token) token.substringAfter('=') else null
                // A --flag=value token carries its own value; a bare flag
                // takes the next token when it does not start with '-'.
                fun takeValue(): String? {
                    if (inlineValue != null) return inlineValue
                    val next = valueAt(i)
                    return if (next != null && !next.startsWith("-")) {
                        i++
                        next
                    } else {
                        null
                    }
                }
                when (flag) {
                    in BLOCKED_VALUE_FLAGS, in BLOCKED_BOOL_FLAGS -> {
                        // Service-owned: dropped; the value goes with it.
                        // Recorded so the editor can say what was dropped.
                        dropped += flag
                        if (flag in BLOCKED_VALUE_FLAGS && inlineValue == null) {
                            if (valueAt(i)?.startsWith("-") == false) i++
                        }
                        i++
                    }
                    in SESSION_FLAGS -> {
                        i++ // bind / reconnect markers: the service re-adds them.
                    }
                    "--protocol" -> {
                        takeValue()?.let { protocol = it }
                        i++
                    }
                    "--masque" -> {
                        protocol = PROTOCOL_MASQUE
                        i++
                    }
                    "--wg", "--wireguard", "--warp" -> {
                        protocol = PROTOCOL_WG
                        i++
                    }
                    "--gool", "--wiw" -> {
                        protocol = PROTOCOL_WG_OVER_MASQUE
                        i++
                    }
                    "--gool-classic" -> {
                        goolClassic = true
                        i++
                    }
                    "--mim", "--masque-in-masque" -> {
                        protocol = PROTOCOL_MIM
                        i++
                    }
                    "--scan", "--turbo", "--balanced", "--thorough", "--verified", "--stealth", "--ironclad" -> {
                        scanMode = when (flag) {
                            "--turbo" -> SCAN_TURBO
                            "--balanced" -> SCAN_BALANCED
                            "--thorough" -> SCAN_THOROUGH
                            "--verified", "--stealth" -> SCAN_VERIFIED
                            "--ironclad" -> SCAN_IRONCLAD
                            else -> takeValue() ?: SCAN_BALANCED
                        }
                        i++
                    }
                    "--h2", "--http2" -> {
                        transport = TRANSPORT_H2
                        transportSeen = true
                        i++
                    }
                    "--h3", "--quic" -> {
                        transport = TRANSPORT_H3
                        transportSeen = true
                        i++
                    }
                    "--dual" -> {
                        ipVersion = IP_BOTH
                        i++
                    }
                    "--ip" -> {
                        takeValue()?.let { ipVersion = it }
                        i++
                    }
                    "--noize" -> {
                        takeValue()?.let { noize = it }
                        i++
                    }
                    "--peer" -> {
                        takeValue()?.let { peer = it }
                        i++
                    }
                    "--wg-peer" -> {
                        takeValue()?.let { peer = it }
                        i++
                    }
                    "--gool-peer" -> {
                        takeValue()?.let { innerPeer = it }
                        i++
                    }
                    "--wiw-outer", "--gool-outer", "--outer-peer" -> {
                        takeValue()?.let { outerPeer = it }
                        i++
                    }
                    "--wiw-inner", "--gool-inner", "--inner-peer" -> {
                        takeValue()?.let { innerPeer = it }
                        i++
                    }
                    "--wiw-peers", "--gool-peers", "--mim-peers" -> {
                        takeValue()
                        i++
                    }
                    "--wiw-scan", "--gool-scan", "--mim-scan" -> {
                        i++
                    }
                    "--mim-outer" -> {
                        takeValue()?.let { outerPeer = it }
                        i++
                    }
                    "--mim-inner" -> {
                        takeValue()?.let { innerPeer = it }
                        i++
                    }
                    "--masque-sni" -> {
                        takeValue()?.let { masqueSni = it }
                        i++
                    }
                    "--dns" -> {
                        takeValue()?.let { dns = it }
                        i++
                    }
                    "--exit-loc" -> {
                        takeValue()?.let { exitLoc = it }
                        i++
                    }
                    "--fragment" -> {
                        fragment = true
                        i++
                    }
                    "--fragment-size" -> {
                        takeValue()?.let { fragmentSize = it }
                        i++
                    }
                    "--fragment-delay" -> {
                        takeValue()?.let { fragmentDelay = it }
                        i++
                    }
                    "--ech" -> {
                        val v = takeValue()
                        if (v == null || v == "auto") {
                            ech = true
                        } else {
                            // A given base64 key, not "auto": kept verbatim.
                            hadUnknown = true
                            unknown += "--ech"
                            unknown += v
                        }
                        i++
                    }
                    "--ech-dns" -> {
                        takeValue()?.let { echDns = it }
                        i++
                    }
                    "--ech-domain" -> {
                        takeValue()?.let { echDomain = it }
                        i++
                    }
                    "--tls-groups" -> {
                        takeValue()?.let {
                            consumedTls += "--tls-groups"
                            consumedTls += it
                        }
                        i++
                    }
                    "--tls-alps" -> {
                        takeValue()?.let {
                            consumedTls += "--tls-alps"
                            consumedTls += it
                        }
                        i++
                    }
                    "--tls-cert-compression" -> {
                        takeValue()?.let {
                            consumedTls += "--tls-cert-compression"
                            consumedTls += it
                        }
                        i++
                    }
                    "--tls13-ciphers" -> {
                        takeValue()?.let {
                            consumedTls += "--tls13-ciphers"
                            consumedTls += it
                        }
                        i++
                    }
                    "--disable-grease" -> {
                        consumedTls += "--disable-grease"
                        i++
                    }
                    "--enable-ech-grease" -> {
                        consumedTls += "--enable-ech-grease"
                        i++
                    }
                    "--disable-ocsp-stapling" -> {
                        consumedTls += "--disable-ocsp-stapling"
                        i++
                    }
                    "--disable-sct" -> {
                        consumedTls += "--disable-sct"
                        i++
                    }
                    "--disable-session-ticket" -> {
                        consumedTls += "--disable-session-ticket"
                        i++
                    }
                    "--disable-extension-permutation" -> {
                        consumedTls += "--disable-extension-permutation"
                        i++
                    }
                    "--psiphon" -> {
                        psiphon = PSIPHON_CHAIN
                        i++
                    }
                    "--psiphon-reverse" -> {
                        psiphon = PSIPHON_REVERSE
                        i++
                    }
                    "--psiphon-only" -> {
                        psiphon = PSIPHON_ONLY
                        i++
                    }
                    "--psiphon-mode" -> {
                        takeValue()?.let { psiphonMode = it }
                        i++
                    }
                    "--psiphon-region" -> {
                        takeValue()?.let { psiphonRegion = it }
                        i++
                    }
                    "--psiphon-cdn-ips" -> {
                        takeValue()?.let { psiphonCdnIps = it }
                        i++
                    }
                    "--psiphon-cdn-sni" -> {
                        takeValue()?.let { psiphonCdnSni = it }
                        i++
                    }
                    "--psiphon-cdn-sets" -> {
                        takeValue()?.let { psiphonCdnSets = it }
                        i++
                    }
                    "--psiphon-server-entries" -> {
                        val v = takeValue()
                        if (v == null || v == "shipped-list") {
                            psiphonBundledList = true
                        } else {
                            // A real custom path, not the bundled list:
                            // kept verbatim instead of rewritten.
                            hadUnknown = true
                            unknown += "--psiphon-server-entries"
                            unknown += v
                        }
                        i++
                    }
                    "--reconnect-secs" -> {
                        takeValue()?.let { reconnectSecs = it }
                        i++
                    }
                    "--keepalive" -> {
                        takeValue()?.let { keepaliveSecs = it }
                        i++
                    }
                    "--validate-secs" -> {
                        takeValue()?.let { validateSecs = it }
                        i++
                    }
                    "--startup-secs" -> {
                        takeValue()?.let { startupSecs = it }
                        i++
                    }
                    "--perf" -> {
                        takeValue()?.let { perf = it }
                        i++
                    }
                    "--no-quic-v2" -> {
                        noQuicV2 = true
                        i++
                    }
                    "--h2-peer" -> {
                        takeValue()?.let { h2Peer = it }
                        i++
                    }
                    "--route-block" -> {
                        takeValue()?.let { routeBlock = it }
                        i++
                    }
                    "--route-direct" -> {
                        takeValue()?.let { routeDirect = it }
                        i++
                    }
                    "--upstream" -> {
                        takeValue()?.let { upstream = it }
                        i++
                    }
                    else -> {
                        // Unknown: kept verbatim in the extra-args text,
                        // with its value when the next token looks like one.
                        hadUnknown = true
                        unknown += token
                        val next = valueAt(i)
                        if (!token.contains('=') && next != null && !next.startsWith("-")) {
                            unknown += next
                            i++
                        }
                        i++
                    }
                }
            }
            if (protocol == PROTOCOL_WG_OVER_MASQUE && goolClassic) {
                protocol = PROTOCOL_GOOL
            }
            // "--protocol gool" is the core word for both modern gool
            // (WireGuard over MASQUE) and classic gool; only
            // --gool-classic tells them apart. Without it, gool means
            // the modern one — otherwise a re-save would add
            // --gool-classic and change the protocol.
            if (protocol == PROTOCOL_GOOL && !goolClassic) {
                protocol = PROTOCOL_WG_OVER_MASQUE
            }
            if (protocol == PROTOCOL_GOOL || protocol == PROTOCOL_MIM ||
                protocol == PROTOCOL_WG_OVER_MASQUE
            ) {
                // Two-hop profiles carry the outer hop in --peer (gool)
                // or --mim-outer/--wiw-outer: the editor's outer field
                // is the single place for it, so a --peer read above
                // moves there when no explicit outer flag was given.
                if (outerPeer.isBlank() && peer.isNotBlank()) {
                    outerPeer = peer
                }
                peer = ""
            }
            // No transport flag means H2: this app's stored profiles
            // (automatic, link, and older editor saves) all mean H2 when
            // silent, and toArgs now always writes --h3 for H3 — so a
            // silent profile is never a round-tripped H3.
            if (!transportSeen) transport = TRANSPORT_H2
            // The fingerprint is a preset only when the stored TLS
            // tokens are exactly that preset's bundle; anything else is
            // kept verbatim as extra args, so editing never rewrites a
            // handshake the editor does not model. No TLS tokens at all
            // means chrome (the core default).
            val consumedSet = consumedTls.toSet()
            val fingerprint = when {
                consumedSet.isEmpty() -> FINGERPRINT_CHROME
                consumedSet == fingerprintArgs(FINGERPRINT_CHROME_120).toSet() -> FINGERPRINT_CHROME_120
                consumedSet == fingerprintArgs(FINGERPRINT_FIREFOX).toSet() -> FINGERPRINT_FIREFOX
                consumedSet == fingerprintArgs(FINGERPRINT_SEMI_PYTHON).toSet() -> FINGERPRINT_SEMI_PYTHON
                consumedSet == fingerprintArgs(FINGERPRINT_GO).toSet() -> FINGERPRINT_GO
                else -> {
                    hadUnknown = true
                    unknown += consumedTls
                    FINGERPRINT_CHROME
                }
            }
            return AetherManualSettings(
                protocol = protocol,
                scanMode = scanMode,
                transport = transport,
                ipVersion = ipVersion,
                noize = noize,
                peer = peer,
                outerPeer = outerPeer,
                innerPeer = innerPeer,
                masqueSni = masqueSni,
                dns = dns,
                exitLoc = exitLoc,
                fragment = fragment,
                fragmentSize = fragmentSize,
                fragmentDelay = fragmentDelay,
                ech = ech,
                echDns = echDns,
                echDomain = echDomain,
                fingerprint = fingerprint,
                psiphon = psiphon,
                psiphonMode = psiphonMode,
                psiphonRegion = psiphonRegion,
                psiphonCdnIps = psiphonCdnIps,
                psiphonCdnSni = psiphonCdnSni,
                psiphonCdnSets = psiphonCdnSets,
                psiphonBundledList = psiphonBundledList,
                reconnectSecs = reconnectSecs,
                keepaliveSecs = keepaliveSecs,
                validateSecs = validateSecs,
                startupSecs = startupSecs,
                perf = perf,
                noQuicV2 = noQuicV2,
                h2Peer = h2Peer,
                extraArgs = unknown.joinToString(" ") { quoteExtraArg(it) },
                routeBlock = routeBlock,
                routeDirect = routeDirect,
                upstream = upstream,
                hasUnknownArgs = hadUnknown,
                droppedFlags = dropped.distinct(),
            )
        }
    }
}
