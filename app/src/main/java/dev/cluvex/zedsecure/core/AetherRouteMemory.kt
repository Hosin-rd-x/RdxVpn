package dev.cluvex.zedsecure.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Which Aether route last worked, per network — the memory behind the
 * quick-first ladder. Idea only, reimplemented here (WhiteAestherMobile's
 * RouteMemory: per-network winner, 14-day forget, 6-hour negative
 * memory); no code was copied.
 *
 * Storage: ONE JSON string in the existing `zed_settings`
 * SharedPreferences file (the same file SettingsRepository uses). No
 * database: a handful of entries, read on every auto-config run and
 * written on every success, is not worth one.
 *
 * The network key comes from connectivity characteristics ONLY
 * (transport types + DNS servers from LinkProperties). NEVER SIM,
 * carrier, operator, or phone info: a SIM and its firewall can
 * mismatch, and detection here is by network behavior only. When the
 * network cannot be told apart from any other, the key is "*".
 *
 * The stored data never leaves the device: it is local memory only,
 * never uploaded anywhere.
 */
object AetherRouteMemory {

    /** For a network that could not be told apart from any other. */
    const val ANY_NETWORK = "*"

    /** Enough for home, work and a few places in between. */
    const val MAX_NETWORKS = 32

    /**
     * A winner older than this is a guess: networks change what they
     * block, and a guess that goes first costs its whole budget when
     * it is wrong.
     */
    const val FORGET_AFTER_MS = 14L * 24 * 60 * 60 * 1_000

    /**
     * How long a failed method stays out of the lead on this network.
     * Deliberately much shorter than [FORGET_AFTER_MS]: a negative
     * held for a fortnight would keep the fastest path out of the
     * lead on a network that came good the same afternoon. Six hours
     * covers the session someone is actually having.
     */
    const val FAILED_RETRY_AFTER_MS = 6L * 60 * 60 * 1_000

    private const val PREFS_NAME = "zed_settings"
    private const val KEY = "aether_route_memory_v1"

    @Serializable
    private data class Entry(
        val method: String,
        val peer: String = "",
        val atMs: Long,
    )

    @Serializable
    private data class Store(
        val entries: Map<String, Entry> = emptyMap(),
        /** network key -> method -> timestamp of the failure. */
        val failedAt: Map<String, Map<String, Long>> = emptyMap(),
    )

    /** A recalled winner: the method that worked here, and its peer. */
    data class Recalled(val method: String, val peer: String)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The winner for the CURRENT network, if one was recorded within
     * [FORGET_AFTER_MS]. Null when nothing is remembered (or it is
     * too old to trust as the lead).
     */
    fun recall(context: Context): Recalled? {
        val key = networkKey(context)
        val entry = read(context).entries[key] ?: return null
        if (System.currentTimeMillis() - entry.atMs > FORGET_AFTER_MS) return null
        if (entry.method.isBlank()) return null
        return Recalled(entry.method, entry.peer)
    }

    /** Methods that failed on the CURRENT network within [FAILED_RETRY_AFTER_MS]. */
    fun failedMethods(context: Context): Set<String> {
        val key = networkKey(context)
        val failed = read(context).failedAt[key] ?: return emptySet()
        val now = System.currentTimeMillis()
        return failed.filter { now - it.value <= FAILED_RETRY_AFTER_MS }.keys
    }

    /** Record a win for the CURRENT network; clears that method's failure mark. */
    fun rememberWin(context: Context, method: String, peer: String) {
        if (method.isBlank()) return
        val key = networkKey(context)
        val now = System.currentTimeMillis()
        update(context) { store ->
            val entries = store.entries.toMutableMap()
            entries[key] = Entry(method, peer, now)
            val trimmed = if (entries.size > MAX_NETWORKS) {
                entries.entries.sortedBy { it.value.atMs }
                    .dropLast(MAX_NETWORKS)
                    .map { it.key }.toSet()
                    .let { drop -> entries.filterKeys { it !in drop } }
            } else {
                entries
            }
            val failed = store.failedAt.toMutableMap()
            val methods = failed[key]?.toMutableMap()
            methods?.remove(method)
            if (methods.isNullOrEmpty()) failed.remove(key) else failed[key] = methods
            store.copy(entries = trimmed, failedAt = failed)
        }
    }

    /** Record failures for the CURRENT network (methods tried, none worked). */
    fun rememberFailures(context: Context, methods: Collection<String>) {
        val usable = methods.filter { it.isNotBlank() }.toSet()
        if (usable.isEmpty()) return
        val key = networkKey(context)
        val now = System.currentTimeMillis()
        update(context) { store ->
            val failed = store.failedAt.toMutableMap()
            val methods = (failed[key] ?: emptyMap()).toMutableMap()
            for (method in usable) methods[method] = now
            failed[key] = methods
            store.copy(failedAt = failed)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    private fun read(context: Context): Store {
        val raw = prefs(context).getString(KEY, null) ?: return Store()
        return runCatching { json.decodeFromString<Store>(raw) }.getOrDefault(Store())
    }

    @Synchronized
    private fun update(context: Context, change: (Store) -> Store) {
        val next = change(read(context))
        val raw = runCatching { json.encodeToString(next) }.getOrNull() ?: return
        prefs(context).edit().putString(KEY, raw).apply()
    }

    /**
     * Network identity from connectivity characteristics only: the
     * transports of the active network plus its DNS servers (which
     * differ per Wi-Fi router and per carrier, so two networks that
     * block different things get different keys). No SIM, carrier,
     * operator, SSID, or phone info is read anywhere here.
     */
    internal fun networkKey(context: Context): String {
        val manager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ANY_NETWORK
        val network = manager.activeNetwork ?: return ANY_NETWORK
        val caps = manager.getNetworkCapabilities(network)
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cell",
            NetworkCapabilities.TRANSPORT_ETHERNET to "eth",
            NetworkCapabilities.TRANSPORT_BLUETOOTH to "bt",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
        ).filter { (type, _) -> caps?.hasTransport(type) == true }.map { it.second }
        val dns = runCatching { manager.getLinkProperties(network) }
            .getOrNull()?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.sorted().orEmpty()
        val parts = (transports + dns).filter { it.isNotBlank() }
        return if (parts.isEmpty()) ANY_NETWORK else parts.joinToString("|")
    }
}
