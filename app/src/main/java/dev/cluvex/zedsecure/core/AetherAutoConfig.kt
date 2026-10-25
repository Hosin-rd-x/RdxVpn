package dev.cluvex.zedsecure.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.domain.config.AetherProfile
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-tap Aether setup: probes the real network, picks the first candidate
 * that fully verifies, and returns it as an [AetherProfile] ready to save.
 * Nothing is saved here — the caller saves the returned profile, makes it
 * active and starts it. A failure returns [Result.failure] with a
 * ready-to-show Persian message and leaves no half-saved profile behind.
 *
 * PROBING BY BEHAVIOR ONLY. The SIM operator is never read (no
 * TelephonyManager carrier/subscriber info anywhere in this file): the
 * user's notes warn that a SIM and its firewall can mismatch (MCI SIM
 * behind an Irancell firewall and vice versa), and other ISPs use one of
 * the two firewalls. So each candidate is judged only by whether its own
 * tunnel actually carries traffic.
 *
 * Every Aether CLI flag used here was verified against
 * aether/src/cli.rs (v2.4.0). Flags that do NOT exist in that CLI are
 * never invented: the corresponding user-note setting is stored in the
 * matching [AetherProfile] field instead, marked NOT-YET-CONSUMED below,
 * so a later engine change can pick it up without re-deriving the notes.
 */
object AetherAutoConfig {

    /** SOCKS port the probe tunnel listens on (the engine's port). */
    private const val PROBE_PORT = AetherBinary.SOCKS_PORT

    /** Per-candidate budget: scan + tunnel + trace must fit inside this. */
    private const val CANDIDATE_TIMEOUT_MS = 75_000L

    /** Whole-run budget across all candidates, so a run always ends. */
    private const val OVERALL_TIMEOUT_MS = 240_000L

    private const val POLL_DELAY_MS = 1_000L
    private const val TRACE_TIMEOUT_MS = 10_000
    private const val TRACE_ATTEMPTS = 3
    private const val TRACE_RETRY_DELAY_MS = 2_000L
    private const val TCP_TIMEOUT_MS = 3_000
    private const val IPV6_DIAL_TIMEOUT_MS = 3_000

    private const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    /** Cloudflare DNS over IPv6, used only as a short "is IPv6 really usable" dial. */
    private const val IPV6_PROBE_HOST = "2606:4700:4700::1111"
    private const val IPV6_PROBE_PORT = 443

    private data class Candidate(
        val method: String,
        val displayName: String,
        val args: List<String>,
        val address: String = "",
        val ipVersion: String = "any",
        val fingerprint: String = "",
        val cipherSuites: String = "",
        val finalMask: String = "",
        val echConfigList: String = "",
        val alpn: String = "",
        /** Skip this candidate entirely when IPv6 is unavailable (not an error). */
        val requiresIpv6: Boolean = false,
        /** Progress string resource shown while this candidate is probed. */
        val progressRes: Int,
    )

    /** Result of one candidate probe: success, or the exact failure reason. */
    private data class ProbeOutcome(
        val success: Boolean,
        val reason: String,
        val logTail: String = "",
    )

    suspend fun runAuto(context: Context, onProgress: (String) -> Unit): Result<AetherProfile> {
        // The callback touches Compose state in the sheet, so it always runs
        // on Main even though the probing itself runs on IO.
        suspend fun progress(res: Int) {
            val text = context.getString(res)
            withContext(Dispatchers.Main) { onProgress(text) }
        }
        fun fail(res: Int): Result<AetherProfile> =
            Result.failure(Exception(context.getString(res)))

        // Defense in depth next to the UI gate: the engine refuses Aether
        // below API 29 too, so probing there could never produce a usable
        // profile.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return fail(R.string.aether_requires_android_10)
        }

        return withContext(Dispatchers.IO) {
            if (!hasNetwork(context)) {
                return@withContext fail(R.string.aether_err_no_network)
            }

            // If something already answers on the probe port, the trace check
            // below could "verify" a tunnel this probe never started — and a
            // probe launch would also truncate the live engine's aether.log
            // (AetherBinary.start truncates it on every start). Refuse
            // instead of risking a false-positive profile.
            if (tcpOpen("127.0.0.1", PROBE_PORT, TCP_TIMEOUT_MS)) {
                return@withContext fail(R.string.aether_err_port_busy)
            }

            val binary = AetherBinary.ensureInstalled(context).getOrNull()
            if (binary == null) {
                // Distinguish the two user-actionable causes: the asset is
                // absent (binary missing) vs. present but not executable
                // (permission denied).
                val file = AetherBinary.binaryFile(context)
                return@withContext if (file.exists() && !file.canExecute()) {
                    fail(R.string.aether_err_permission)
                } else {
                    fail(R.string.aether_err_binary_missing)
                }
            }
            if (!binary.canExecute()) {
                return@withContext fail(R.string.aether_err_permission)
            }

            val ipv6Available = hasGlobalIpv6(context) || ipv6DialOk()
            val workDir = AetherBinary.configDir(context)

            // Candidate order (rationale): MASQUE-H2 works on both
            // firewalls per the notes, so it goes first; WARP is the
            // general fallback; the CDN/WORKER variants are the
            // firewall-specific ones and come last. On Irancell, methods
            // other than the F&F one face upload speed limits, which is
            // why cdn_worker_ff is tried even after the general methods:
            // a working general method is still preferred, and F&F
            // remains as the firewall-specific fallback.
            val candidates = listOf(
                // masque_h2: protocol MASQUE-HTTP/2, scan, pick a working IP.
                // --masque (MASQUE over QUIC/HTTP-3) + --h2 (HTTP/2 instead
                // of HTTP/3) + --scan balanced (collect a few, keep fastest).
                // Default IP version is IPv4, matching the note's MCI and
                // Irancell MASQUE/H2 steps (no IP-type step in either).
                Candidate(
                    method = "masque_h2",
                    displayName = "Aether خودکار – MASQUE",
                    args = listOf("--masque", "--h2", "--scan", "balanced"),
                    progressRes = R.string.aether_progress_masque,
                ),
                // warp: protocol wireguard, scan, pick a working IP.
                // --wg (classic WireGuard) + --scan balanced. IP type IPv6
                // when IPv6 is available, else both (--dual), matching the
                // notes: MCI wants IPv6, Irancell accepts any IP type.
                Candidate(
                    method = "warp",
                    displayName = "Aether خودکار – WARP",
                    args = listOf("--wg", "--scan", "balanced") +
                        if (ipv6Available) listOf("--ip", "v6") else listOf("--dual"),
                    ipVersion = if (ipv6Available) "v6" else "any",
                    progressRes = R.string.aether_progress_warp,
                ),
                // cdn_worker_ipv6 (MCI-style): MASQUE-H2 over IPv6, found by
                // scan. The note's IPv6 address (e.g. in the
                // 2a06:98c1:3101::/32 range) is what the scan picks at
                // connect time, so address stays empty here — the engine
                // re-scans with these args. Note-settings with no Aether
                // CLI flag (verified against cli.rs: no --fingerprint,
                // --cipher-suites, --final-mask or --ech-config-list flag
                // exists) are stored in the profile fields, NOT-YET-CONSUMED
                // by the binary: fingerprint chrome, echConfigList /
                // cipherSuites / finalMask cleared (empty).
                Candidate(
                    method = "cdn_worker_ipv6",
                    displayName = "Aether خودکار – CDN",
                    args = listOf("--masque", "--h2", "--ip", "v6", "--scan", "balanced"),
                    ipVersion = "v6",
                    fingerprint = "chrome", // NOT-YET-CONSUMED by the binary
                    cipherSuites = "", // cleared per note, NOT-YET-CONSUMED
                    finalMask = "", // cleared per note, NOT-YET-CONSUMED
                    echConfigList = "", // cleared per note, NOT-YET-CONSUMED
                    requiresIpv6 = true,
                    progressRes = R.string.aether_progress_cdn,
                ),
                // cdn_worker_ff (Irancell-style F&F): MASQUE-H2 forced to
                // the note's address. --peer forces a peer and skips
                // scanning (per the CLI usage text), so no --scan flag
                // here; :443 is the TLS port of the H2 transport.
                // Note-settings with no Aether CLI flag (verified against
                // cli.rs: no --fingerprint, --cipher-suites, --final-mask,
                // --ech-config-list or --alpn flag exists) are stored in
                // the profile fields, NOT-YET-CONSUMED by the binary:
                // echConfigList cleared, finalMask tlshello-0-len (the F&F
                // value), cipherSuites semi-python (the F&F value),
                // fingerprint unsafe, ALPN http/1.1 for ws and h2,http/1.1
                // for XHTTP (collapsed into the single alpn field).
                Candidate(
                    method = "cdn_worker_ff",
                    displayName = "Aether خودکار – CDN",
                    args = listOf("--masque", "--h2", "--peer", "188.114.98.0:443"),
                    address = "188.114.98.0",
                    fingerprint = "unsafe", // NOT-YET-CONSUMED by the binary
                    cipherSuites = "semi-python", // NOT-YET-CONSUMED by the binary
                    finalMask = "tlshello-0-len", // NOT-YET-CONSUMED by the binary
                    echConfigList = "", // cleared per note, NOT-YET-CONSUMED
                    alpn = "h2,http/1.1", // NOT-YET-CONSUMED by the binary
                    progressRes = R.string.aether_progress_cdn,
                ),
            ).filterNot { it.requiresIpv6 && !ipv6Available }

            // withTimeout (not withTimeoutOrNull): its TimeoutCancellation-
            // Exception is the only thing that means "overall budget spent".
            // A cancellation from outside (sheet dismissed) is a plain
            // CancellationException, which this catch deliberately does not
            // swallow, so it still propagates to the caller.
            val failures = mutableListOf<Pair<Candidate, ProbeOutcome>>()
            val found: Candidate? = try {
                withTimeout(OVERALL_TIMEOUT_MS) {
                    for (candidate in candidates) {
                        progress(candidate.progressRes)
                        val outcome = probeCandidate(context, candidate, workDir)
                        if (outcome.success) {
                            return@withTimeout candidate
                        }
                        failures += candidate to outcome
                        if (!currentCoroutineContext().isActive) break
                    }
                    null
                }
            } catch (e: TimeoutCancellationException) {
                return@withContext fail(R.string.aether_err_timeout)
            }

            if (found != null) {
                Result.success(
                    AetherProfile(
                        method = found.method,
                        args = found.args,
                        displayName = found.displayName,
                        address = found.address,
                        ipVersion = found.ipVersion,
                        fingerprint = found.fingerprint,
                        cipherSuites = found.cipherSuites,
                        finalMask = found.finalMask,
                        echConfigList = found.echConfigList,
                        alpn = found.alpn,
                    ),
                )
            } else {
                // Detailed failure: the sheet shows this message, so the
                // user can screenshot the exact reason per method instead
                // of a generic sentence.
                val detail = buildString {
                    append("هیچ‌کدام از روش‌ها روی این اینترنت الان جواب نداد.")
                    for ((candidate, outcome) in failures) {
                        append("\n\n• ")
                        append(candidate.displayName)
                        append(": ")
                        append(outcome.reason)
                        if (outcome.logTail.isNotBlank()) {
                            append("\n")
                            append(outcome.logTail)
                        }
                    }
                }
                Result.failure(Exception(detail))
            }
        }
    }

    /** Last few lines of the binary's own log (stdout+stderr go there). */
    private fun readLogTail(workDir: java.io.File): String {
        val lines = runCatching {
            java.io.File(workDir, "aether.log").readLines()
        }.getOrDefault(emptyList())
        val ansi = Regex("\u001B\\[[0-9;]*m")
        return lines.filter { it.isNotBlank() }.takeLast(6)
            .joinToString("\n") { ansi.replace(it, "").take(120) }
    }

    /**
     * Runs one candidate's probe tunnel and verifies it end to end, and
     * reports the exact failure reason (not just pass/fail) so the sheet
     * can show why each method failed.
     * The probe process is always killed before returning — success,
     * failure, timeout or cancellation — so a probe can never leak.
     * Probe args add only what a probe needs on top of the candidate's
     * saved args: a fresh scan every time (--no-quick-reconnect, so a
     * stale last-connection gateway cannot fake a pass) and the probe
     * SOCKS bind. Config-path flags are deliberately NOT added: the
     * process runs in the shared config dir, so identities registered
     * by a probe are reused by the real engine instead of registering
     * a second device (upstream rate-limits re-registration).
     * The log is truncated on every probe start, so this candidate's
     * tail is read here, before the next candidate overwrites it.
     */
    private suspend fun probeCandidate(
        context: Context,
        candidate: Candidate,
        workDir: java.io.File,
    ): ProbeOutcome {
        var process: Process? = null
        var outcome: ProbeOutcome? = null
        try {
            if (tcpOpen("127.0.0.1", PROBE_PORT, TCP_TIMEOUT_MS)) {
                outcome = ProbeOutcome(false, "پورت ۱۸۱۹ اشغال بود")
            } else {
                val probeArgs = candidate.args + listOf(
                    "--no-quick-reconnect",
                    "--bind", "127.0.0.1:$PROBE_PORT",
                )
                val started = try {
                    AetherBinary.start(context, probeArgs, workDir)
                } catch (e: Exception) {
                    // No process ran, so the log still holds a previous
                    // candidate's output and would mislead: return now.
                    return ProbeOutcome(
                        false,
                        "اجرا نشد: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
                process = started
                outcome = withTimeoutOrNull(CANDIDATE_TIMEOUT_MS) {
                    // Wait until the probe's SOCKS port answers.
                    while (currentCoroutineContext().isActive) {
                        if (tcpOpen("127.0.0.1", PROBE_PORT, TCP_TIMEOUT_MS)) break
                        if (!started.isAlive) {
                            val code = runCatching { started.exitValue() }.getOrNull()
                            return@withTimeoutOrNull ProbeOutcome(
                                false,
                                "برنامه همان اول بسته شد" +
                                    (code?.let { " (کد $it)" } ?: ""),
                            )
                        }
                        delay(POLL_DELAY_MS)
                    }
                    if (!currentCoroutineContext().isActive) {
                        return@withTimeoutOrNull ProbeOutcome(false, "لغو شد")
                    }
                    // SOCKS answering is not enough: the tunnel must carry a
                    // real HTTPS request. Retry briefly — the tunnel can be
                    // up a moment before it carries traffic.
                    repeat(TRACE_ATTEMPTS) { attempt ->
                        if (traceOk()) return@withTimeoutOrNull ProbeOutcome(true, "")
                        if (attempt + 1 < TRACE_ATTEMPTS) delay(TRACE_RETRY_DELAY_MS)
                    }
                    ProbeOutcome(false, "تونل بالا آمد ولی اینترنت ازش رد نشد")
                } ?: ProbeOutcome(false, "پورت جواب نداد (۷۵ ثانیه)")
            }
        } finally {
            // Runs on success, failure, timeout and cancellation alike.
            // Note: on cancellation, the blocking socket calls above are
            // bounded by their own timeouts, so this finally is reached
            // promptly; stop() itself never suspends.
            AetherBinary.stop(process)
            process = null
        }
        val tail = readLogTail(workDir)
        return outcome?.copy(logTail = tail) ?: ProbeOutcome(false, "نامشخص")
    }

    /** Network availability only — no operator, carrier or SIM info. */
    private fun hasNetwork(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * True when the active network has a global (not loopback, link-local,
     * site-local/unique-local or multicast) IPv6 address.
     */
    private fun hasGlobalIpv6(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val props = manager.getLinkProperties(network) ?: return false
        return props.linkAddresses.any { link ->
            val addr = link.address
            addr is Inet6Address &&
                !addr.isLoopbackAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isSiteLocalAddress &&
                !isUniqueLocalAddress(addr) &&
                !addr.isMulticastAddress
        }
    }

    /** Unique-local fc00::/7 (ULA) is not usable public IPv6. */
    private fun isUniqueLocalAddress(addr: Inet6Address): Boolean {
        val raw = addr.address
        return raw.isNotEmpty() && (raw[0] == 0xFC.toByte() || raw[0] == 0xFD.toByte())
    }

    /** Second chance when LinkProperties shows no global IPv6: one short dial. */
    private fun ipv6DialOk(): Boolean =
        tcpOpen(IPV6_PROBE_HOST, IPV6_PROBE_PORT, IPV6_DIAL_TIMEOUT_MS)

    private fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    /**
     * An HTTPS trace request through the probe's SOCKS proxy. Accepts
     * HTTP 200, or a body reporting warp=on, exactly as Aether's own
     * documented check does (curl -x socks5h://127.0.0.1:1819
     * https://www.cloudflare.com/cdn-cgi/trace).
     */
    private fun traceOk(): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PROBE_PORT))
        val conn = runCatching {
            URL(TRACE_URL).openConnection(proxy) as HttpURLConnection
        }.getOrNull() ?: return false
        conn.connectTimeout = TRACE_TIMEOUT_MS
        conn.readTimeout = TRACE_TIMEOUT_MS
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) return true
            val errorBody = runCatching {
                conn.errorStream?.readBytes()?.decodeToString().orEmpty()
            }.getOrDefault("")
            return errorBody.contains("warp=on")
        } catch (e: Exception) {
            return false
        } finally {
            conn.disconnect()
        }
    }
}
