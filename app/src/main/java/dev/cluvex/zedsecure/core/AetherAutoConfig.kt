package dev.cluvex.zedsecure.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.domain.config.AetherPeer
import dev.cluvex.zedsecure.domain.config.AetherProfile
import dev.cluvex.zedsecure.domain.config.AetherScanResult
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

    /** SOCKS port background-optimizer probes listen on: never the live
     *  session's 1819, the chained tunnel's 1820, or the psiphon-scan
     *  port 1821. Verified unused in the tree. */
    internal const val OPTIMIZER_PORT = 1822

    /** Psiphon listener port for chained scans (the core's own default). */
    private const val PSIPHON_SCAN_PORT = 1821

    /** Per-candidate budget: scan + tunnel + trace must fit inside this. */
    private const val CANDIDATE_TIMEOUT_MS = 75_000L

    /** Memory-rung budget: a recalled winner with --quick-reconnect and
     *  no scan either answers at once or is stale; never wait long. */
    private const val MEMORY_TIMEOUT_MS = 20_000L

    /** Quick-rung budget: the cheap first tries (MASQUE-H2, WARP). */
    private const val QUICK_TIMEOUT_MS = 30_000L

    /** Tactics-rung budget: cheap H3 / IPv6 variants, one step deeper. */
    private const val TACTIC_TIMEOUT_MS = 45_000L

    /** Manual-editor scan budget: one scan with the user's own settings. */
    private const val SCAN_TIMEOUT_MS = 90_000L

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

    internal data class Candidate(
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

    /**
     * The ladder, in order. Shared by [runAuto] and the background
     * optimizer so both probe the exact same definitions: MASQUE-H2
     * first (works on both firewalls per the notes), WARP as the
     * general fallback, then the cheap tactic rungs (H3, IPv6), then
     * the firewall-specific deep candidates (CDN over IPv6, F&F).
     * On Irancell, methods other than the F&F one face upload speed
     * limits, which is why cdn_worker_ff is tried even after the
     * general methods: a working general method is still preferred,
     * and F&F remains as the firewall-specific fallback.
     */
    internal fun buildCandidates(ipv6Available: Boolean): List<Candidate> = listOf(
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
        // masque_h3: the H3/QUIC variant of the quick rung. Cheap
        // (same scan shape as masque_h2) but a genuinely different
        // transport, so it is its own rung after the quick rung.
        Candidate(
            method = "masque_h3",
            displayName = "Aether خودکار – MASQUE H3",
            args = listOf("--masque", "--h3", "--scan", "balanced"),
            progressRes = R.string.aether_progress_masque_h3,
        ),
        // masque_v6: MASQUE-H2 over IPv6 as a tactic rung (same args
        // as the CDN/IPv6 candidate, without its F&F profile fields).
        // Skipped entirely when IPv6 is unavailable.
        Candidate(
            method = "masque_v6",
            displayName = "Aether خودکار – MASQUE IPv6",
            args = listOf("--masque", "--h2", "--ip", "v6", "--scan", "balanced"),
            ipVersion = "v6",
            requiresIpv6 = true,
            progressRes = R.string.aether_progress_masque_v6,
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

    /** Result of one candidate probe: success, or the exact failure reason. */
    internal data class ProbeOutcome(
        val success: Boolean,
        val reason: String,
        val logTail: String = "",
        /** Gateway the probe's log announced as selected ("" if none). */
        val pinnedPeer: String = "",
        /** Median HTTPS-trace latency in ms of the successful samples,
         *  or -1 when the probe never carried a trace. */
        val latencyMs: Long = -1,
        /** Every successful trace sample, in time order (empty when
         *  the probe never carried a trace). The optimizer checks
         *  consecutive samples against the incumbent; the median
         *  alone cannot prove that. */
        val latencySamples: List<Long> = emptyList(),
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

            val ipv6Available = ipv6Available(context)
            val workDir = AetherBinary.configDir(context)

            val candidates = buildCandidates(ipv6Available)

            // Budget ladder (quick-first). Rung 0 is the remembered
            // winner for THIS network: --quick-reconnect, no scan, short
            // budget — either it answers at once or it is stale. Then
            // the cheap rungs (quick, tactics) before the deep ones, so
            // a first connect is fast and a later connect on a known
            // network is faster still. Within each rung, methods that
            // failed on this network recently go last (deprioritized,
            // never skipped).
            //
            // withTimeout (not withTimeoutOrNull): its TimeoutCancellation-
            // Exception is the only thing that means "overall budget spent".
            // A cancellation from outside (sheet dismissed) is a plain
            // CancellationException, which this catch deliberately does not
            // swallow, so it still propagates to the caller.
            val recalled = AetherRouteMemory.recall(context)
            val failedRecently = AetherRouteMemory.failedMethods(context)
            fun deprioritizedLast(rung: List<Candidate>): List<Candidate> =
                rung.sortedBy { if (it.method in failedRecently) 1 else 0 }
            val failures = mutableListOf<Pair<Candidate, ProbeOutcome>>()
            val attempted = mutableListOf<String>()
            val found: Pair<Candidate, ProbeOutcome>? = try {
                withTimeout(OVERALL_TIMEOUT_MS) {
                    // Rung 0: memory. A stale failure here is not added
                    // to the sheet's detail — the same method appears
                    // again in its own rung below.
                    if (recalled != null) {
                        val winner = candidates.firstOrNull { it.method == recalled.method }
                        if (winner != null) {
                            progress(R.string.aether_progress_memory)
                            val outcome = probeCandidate(
                                context,
                                winner,
                                workDir,
                                MEMORY_TIMEOUT_MS,
                                PROBE_PORT,
                                overrideArgs = memoryProbeArgs(winner, recalled.peer),
                            )
                            attempted += winner.method
                            if (outcome.success) {
                                val peer = outcome.pinnedPeer.ifBlank { recalled.peer }
                                return@withTimeout winner to outcome.copy(pinnedPeer = peer)
                            }
                        }
                    }
                    val rungs = listOf(
                        deprioritizedLast(
                            candidates.filter { it.method == "masque_h2" || it.method == "warp" },
                        ) to QUICK_TIMEOUT_MS,
                        deprioritizedLast(
                            candidates.filter { it.method == "masque_h3" || it.method == "masque_v6" },
                        ) to TACTIC_TIMEOUT_MS,
                        deprioritizedLast(
                            candidates.filter {
                                it.method == "cdn_worker_ipv6" || it.method == "cdn_worker_ff"
                            },
                        ) to CANDIDATE_TIMEOUT_MS,
                    )
                    for ((rung, timeoutMs) in rungs) {
                        for (candidate in rung) {
                            progress(candidate.progressRes)
                            val outcome = probeCandidate(
                                context,
                                candidate,
                                workDir,
                                timeoutMs,
                                PROBE_PORT,
                            )
                            attempted += candidate.method
                            if (outcome.success) {
                                return@withTimeout candidate to outcome
                            }
                            failures += candidate to outcome
                            if (!currentCoroutineContext().isActive) break
                        }
                        if (!currentCoroutineContext().isActive) break
                    }
                    null
                }
            } catch (e: TimeoutCancellationException) {
                AetherRouteMemory.rememberFailures(context, attempted)
                return@withContext fail(R.string.aether_err_timeout)
            }

            if (found != null) {
                val (candidate, outcome) = found
                AetherRouteMemory.rememberWin(context, candidate.method, outcome.pinnedPeer)
                // The methods that failed before the winner are still
                // failures for THIS network: the next run deprioritizes
                // them, and the background optimizer never wastes a
                // probe re-trying one that just failed.
                AetherRouteMemory.rememberFailures(
                    context,
                    failures.map { it.first.method },
                )
                Result.success(
                    AetherProfile(
                        method = candidate.method,
                        args = candidate.args,
                        displayName = candidate.displayName,
                        address = candidate.address,
                        ipVersion = candidate.ipVersion,
                        fingerprint = candidate.fingerprint,
                        cipherSuites = candidate.cipherSuites,
                        finalMask = candidate.finalMask,
                        echConfigList = candidate.echConfigList,
                        alpn = candidate.alpn,
                        pinnedPeer = outcome.pinnedPeer,
                    ),
                )
            } else {
                AetherRouteMemory.rememberFailures(context, attempted)
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

    /**
     * Probe arguments for the memory rung: the candidate's args with
     * every scan flag stripped (a memory hit must never re-scan),
     * any stored `--peer` replaced by the remembered peer, and
     * `--quick-reconnect` added. Candidate args here are simple
     * space-separated tokens (no `=` forms, no two-hop flags), so a
     * small local strip is exact.
     */
    private fun memoryProbeArgs(candidate: Candidate, peer: String): List<String> {
        val args = mutableListOf<String>()
        var i = 0
        while (i < candidate.args.size) {
            when (candidate.args[i]) {
                "--scan" -> i += 2
                "--peer" -> i += 2
                "--quick-reconnect", "--no-quick-reconnect" -> i += 1
                else -> {
                    args += candidate.args[i]
                    i += 1
                }
            }
        }
        if (peer.isNotBlank()) {
            args += "--peer"
            args += AetherPeer.normalize(peer)
        }
        args += "--quick-reconnect"
        return args
    }

    /**
     * Manual-editor scan: runs ONE probe with the editor's own settings
     * (scan form, with --no-quick-reconnect, the probe bind, and for
     * chained scans --psiphon-bind 127.0.0.1:1821, all added by the
     * caller) and returns the gateway(s) the binary selected, or a
     * failure with a ready-to-show message. The probe process is always
     * killed before returning, and the returned gateway is only ever
     * filled into the editor's endpoint fields — connecting never
     * re-scans. Verification is the same bar as the one-tap probes: the
     * SOCKS port must answer AND carry a real HTTPS trace.
     *
     * The probe runs in its own work directory ([AetherBinary.scanDir]),
     * never the session's: starting a process truncates its log, and a
     * scan must not wipe a live session's log. A scan also refuses to
     * run while a session is live at all.
     */
    suspend fun scanEndpoint(
        context: Context,
        scanArgs: List<String>,
        onProgress: (String) -> Unit = {},
    ): Result<AetherScanResult> = withContext(Dispatchers.IO) {
        fun fail(res: Int): Result<AetherScanResult> =
            Result.failure(Exception(context.getString(res)))
        // A scan must never run while a session is live: a second core
        // would truncate the session's log and fight it for the ports.
        // The port checks below are only the backstop — a session that
        // is still connecting may not have bound yet.
        if (VpnManager.status.value.state.isActive) {
            return@withContext fail(R.string.aether_err_port_busy)
        }
        val binary = AetherBinary.ensureInstalled(context).getOrNull()
        if (binary == null) {
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
        // Leftovers from an earlier scan or session can still hold the
        // ports; clear them before checking. Safe here: no session is
        // live (checked above), and the checks below re-verify.
        AetherBinary.killOrphans(context)
        val chainedScan = scanArgs.any { it == "--psiphon" || it == "--psiphon-reverse" }
        if (tcpOpen("127.0.0.1", PROBE_PORT, TCP_TIMEOUT_MS) ||
            (chainedScan && tcpOpen("127.0.0.1", PSIPHON_SCAN_PORT, TCP_TIMEOUT_MS))
        ) {
            return@withContext fail(R.string.aether_err_port_busy)
        }
        val workDir = AetherBinary.scanDir(context)
        var process: Process? = null
        try {
            val started = try {
                AetherBinary.start(context, scanArgs, workDir)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    Exception("اجرا نشد: ${e.message ?: e.javaClass.simpleName}"),
                )
            }
            process = started
            val verified = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                while (currentCoroutineContext().isActive) {
                    if (tcpOpen("127.0.0.1", PROBE_PORT, TCP_TIMEOUT_MS)) break
                    if (!started.isAlive) {
                        return@withTimeoutOrNull false
                    }
                    delay(POLL_DELAY_MS)
                }
                if (!currentCoroutineContext().isActive) {
                    return@withTimeoutOrNull false
                }
                repeat(TRACE_ATTEMPTS) { attempt ->
                    if (traceOk(PROBE_PORT)) return@withTimeoutOrNull true
                    if (attempt + 1 < TRACE_ATTEMPTS) delay(TRACE_RETRY_DELAY_MS)
                }
                false
            } ?: false
            if (!verified) {
                return@withContext fail(R.string.aether_err_scan)
            }
            val (gateway, innerGateway) =
                parseSelectedGateways(AetherBinary.logTail(workDir, 2000))
            if (gateway.isBlank()) {
                return@withContext fail(R.string.aether_err_scan)
            }
            Result.success(AetherScanResult(gateway, innerGateway))
        } finally {
            // Same guarantee as the one-tap probes: the scan process
            // never outlives this call.
            AetherBinary.stop(process)
            process = null
        }
    }

    /** Last few lines of the binary's own log (stdout+stderr go there). */
    private fun readLogTail(workDir: java.io.File): String =
        AetherBinary.logTail(workDir)

    /**
     * Gateways the binary picked, read from its own log lines: the outer
     * hop, plus the inner hop when the run announced both. Two-hop runs
     * announce both hops on one line — gool logs "using cloudflare edge
     * {outer} (outer) and {inner} (inner)", MIM logs "masque-in-masque
     * ready: {outer} (outer) and {inner} (inner)" — and that line wins
     * over the single-pick lines, because for MIM the core logs two
     * "selected MASQUE gateway" picks (outer, then inner-avoiding) and
     * only the pair line says which is which. Single-hop runs announce
     * one gateway. Returns ("", "") when the binary announces no pick.
     * Only the "selected ..." announcement counts — the "candidate ok
     * ..." lines are gateways merely tried, not the one chosen, and must
     * never be pinned.
     */
    private fun parseSelectedGateways(logText: String): Pair<String, String> {
        // Every captured gateway is normalized (AetherPeer): the core
        // announces IPv6 gateways unbracketed, and pinning that form
        // verbatim makes every later --peer connect die with "bad peer
        // address". Normalizing here means no new bad value is stored.
        fun clean(s: String): String = AetherPeer.normalize(s.trimEnd(',', ';'))
        Regex("""edge (\S+) \(outer\) and (\S+) \(inner\)""").find(logText)?.let {
            return clean(it.groupValues[1]) to clean(it.groupValues[2])
        }
        Regex("""ready: (\S+) \(outer\) and (\S+) \(inner\)""").find(logText)?.let {
            return clean(it.groupValues[1]) to clean(it.groupValues[2])
        }
        Regex("""selected MASQUE gateway (\S+)""").find(logText)?.let {
            return clean(it.groupValues[1]) to ""
        }
        Regex("""selected WireGuard endpoint (\S+)""").find(logText)?.let {
            return clean(it.groupValues[1]) to ""
        }
        // Single-hop ready line. Only reached when no pick line above
        // matched; \S+ stops at the space, so it cannot truncate a peer.
        Regex("""using cloudflare edge (\S+)""").find(logText)?.let {
            return clean(it.groupValues[1]) to ""
        }
        return "" to ""
    }

    /**
     * Gateway the binary picked, read from its own log lines. Only the
     * "selected ..." announcement counts — the "candidate ok ..." lines
     * are gateways merely tried, not the one chosen, and must never be
     * pinned. Returns "" when the binary announces no pick.
     */
    private fun parseSelectedGateway(logText: String): String =
        parseSelectedGateways(logText).first

    /**
     * Runs one candidate's probe tunnel and verifies it end to end, and
     * reports the exact failure reason (not just pass/fail) so the sheet
     * can show why each method failed.
     * The probe process is always killed before returning — success,
     * failure, timeout or cancellation — so a probe can never leak.
     * By default the probe adds a fresh scan every time
     * (--no-quick-reconnect, so a stale last-connection gateway cannot
     * fake a pass) and the probe SOCKS bind. [overrideArgs] replaces
     * the candidate's args wholesale (memory rung: pre-built
     * --quick-reconnect args, no scan flag added). Config-path flags
     * are deliberately NOT added: the process runs in the shared
     * config dir, so identities registered by a probe are reused by
     * the real engine instead of registering a second device
     * (upstream rate-limits re-registration).
     * The log is truncated on every probe start, so this candidate's
     * tail is read here, before the next candidate overwrites it.
     *
     * [extraSamples] takes additional spaced trace samples through the
     * same verified probe process (optimizer stability measurement);
     * [ProbeOutcome.latencyMs] is the median of all successful samples.
     * Internal (not private) so the background optimizer can reuse the
     * exact same probing with its own port, timeout and work dir.
     */
    internal suspend fun probeCandidate(
        context: Context,
        candidate: Candidate,
        workDir: java.io.File,
        timeoutMs: Long = CANDIDATE_TIMEOUT_MS,
        probePort: Int = PROBE_PORT,
        overrideArgs: List<String>? = null,
        extraSamples: Int = 0,
    ): ProbeOutcome {
        var process: Process? = null
        var outcome: ProbeOutcome? = null
        try {
            if (tcpOpen("127.0.0.1", probePort, TCP_TIMEOUT_MS)) {
                outcome = ProbeOutcome(false, "پورت $probePort اشغال بود")
            } else {
                val baseArgs = overrideArgs
                    ?: (candidate.args + "--no-quick-reconnect")
                val probeArgs = baseArgs + listOf(
                    "--bind", "127.0.0.1:$probePort",
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
                outcome = withTimeoutOrNull(timeoutMs) {
                    // Wait until the probe's SOCKS port answers.
                    while (currentCoroutineContext().isActive) {
                        if (tcpOpen("127.0.0.1", probePort, TCP_TIMEOUT_MS)) break
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
                        val firstMs = traceLatencyMs(probePort)
                        if (firstMs != null) {
                            val samples = mutableListOf(firstMs)
                            repeat(extraSamples) {
                                delay(TRACE_RETRY_DELAY_MS)
                                traceLatencyMs(probePort)?.let { samples += it }
                            }
                            val sorted = samples.sorted()
                            return@withTimeoutOrNull ProbeOutcome(
                                true,
                                "",
                                latencyMs = sorted[sorted.size / 2],
                                latencySamples = samples,
                            )
                        }
                        if (attempt + 1 < TRACE_ATTEMPTS) delay(TRACE_RETRY_DELAY_MS)
                    }
                    ProbeOutcome(false, "تونل بالا آمد ولی اینترنت ازش رد نشد")
                } ?: ProbeOutcome(false, "پورت جواب نداد (${timeoutMs / 1000} ثانیه)")
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
        // Pin the gateway this probe verified, but ONLY on success: a
        // failed probe's log must never pin anything. The binary
        // announces its pick with a "selected ..." line (the same lines
        // PattNG's scanner reads); when it announces none, pinnedPeer
        // stays empty and the service falls back to --quick-reconnect.
        val pinned = if (outcome?.success == true) {
            parseSelectedGateway(AetherBinary.logTail(workDir, 2000))
        } else {
            ""
        }
        return outcome?.copy(logTail = tail, pinnedPeer = pinned)
            ?: ProbeOutcome(false, "نامشخص")
    }

    /** IPv6 usable on the active network, by the same two checks
     *  runAuto uses. The background optimizer reuses it so its
     *  candidate list matches the ladder's. */
    internal fun ipv6Available(context: Context): Boolean =
        hasGlobalIpv6(context) || ipv6DialOk()

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
     * An HTTPS trace request through a SOCKS proxy on [port]. Accepts
     * HTTP 200, or a body reporting warp=on, exactly as Aether's own
     * documented check does (curl -x socks5h://127.0.0.1:1819
     * https://www.cloudflare.com/cdn-cgi/trace). Returns the request
     * latency in ms, or null when the tunnel does not carry traffic.
     * Blocking socket work: call only from a background dispatcher.
     */
    internal fun traceLatencyMs(port: Int): Long? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val conn = runCatching {
            URL(TRACE_URL).openConnection(proxy) as HttpURLConnection
        }.getOrNull() ?: return null
        conn.connectTimeout = TRACE_TIMEOUT_MS
        conn.readTimeout = TRACE_TIMEOUT_MS
        val startMs = SystemClock.elapsedRealtime()
        try {
            val code = conn.responseCode
            val ok = code == HttpURLConnection.HTTP_OK ||
                runCatching {
                    conn.errorStream?.readBytes()?.decodeToString().orEmpty()
                }.getOrDefault("").contains("warp=on")
            return if (ok) SystemClock.elapsedRealtime() - startMs else null
        } catch (e: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun traceOk(port: Int): Boolean = traceLatencyMs(port) != null
}
