package dev.cluvex.zedsecure.core.tor

import android.content.Context
import dev.cluvex.zedsecure.core.AppLog as Log
import dev.cluvex.zedsecure.domain.model.AppSettings
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TorController(
    private val context: Context,
    private val settings: AppSettings,
    private val onBootstrapped: () -> Unit,
    private val onStopped: (String?) -> Unit,

    private val upstreamSocksPort: Int? = null,

    private val onProgress: (Int) -> Unit = {},
) {
    private val active = AtomicBoolean(false)
    private var process: Process? = null
    private var reader: Thread? = null

    val socksPort: Int get() = TorConfigBuilder.SOCKS_PORT
    val isRunning: Boolean get() = active.get()

    fun start(): Boolean {
        if (active.get()) stop()
        return try {
            val torDir = File(context.filesDir, "tor").apply { mkdirs() }
            extractAsset("geoip", torDir)
            extractAsset("geoip6", torDir)
            extractAsset("bridges_default.lst", torDir)

            val dataDir = File(context.filesDir, "tor_data").apply { mkdirs() }
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val torrc = File(torDir, "torrc").apply {
                writeText(TorConfigBuilder.build(settings, nativeLibDir, torDir, dataDir, upstreamSocksPort))
            }

            val cmd = mutableListOf(
                "$nativeLibDir/libtor.so", "-f", torrc.absolutePath,
                "--pidfile", File(dataDir, "tor.pid").absolutePath,
            )

            val webtunnel = settings.torBridgesMode != "none" && settings.torBridgeTransport == "webtunnel"
            if (settings.torFakeSniEnabled && !webtunnel) {
                val hosts = settings.torFakeSniHosts.split(',', '\n').map { it.trim() }
                    .filter { it.isNotEmpty() }.ifEmpty { DEFAULT_FAKE_SNI }.joinToString(",")
                cmd += "--fake-hosts"; cmd += hosts
            }
            val pb = ProcessBuilder(cmd)
            pb.environment()["LD_LIBRARY_PATH"] = nativeLibDir
            pb.redirectErrorStream(true)
            val p = pb.start()
            process = p
            active.set(true)

            reader = Thread {
                var bootstrapped = false
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, line)
                        BOOTSTRAP_PCT.find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { pct -> runCatching { onProgress(pct) } }
                        if (!bootstrapped && line.contains("Bootstrapped 100%")) {
                            bootstrapped = true

                            Thread { runCatching { onBootstrapped() } }
                                .apply { isDaemon = true; start() }
                        }
                    }
                }

                if (active.compareAndSet(true, false)) {
                    val code = runCatching { p.waitFor() }.getOrDefault(-1)
                    Log.i(TAG, "tor exited ($code)")
                    onStopped(if (bootstrapped) null else "Tor exited before connecting")
                }
            }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            active.set(false)
            Log.e(TAG, "tor failed to start", e)
            onStopped(e.message ?: "Tor failed to start")
            false
        }
    }

    fun stop() {
        active.set(false)
        val p = process
        process = null
        reader = null
        if (p != null) {
            runCatching { p.destroy() }
            // Wait briefly for the process to actually exit before
            // returning: start() may immediately spawn a replacement,
            // and a still-dying tor would race it for the SOCKS port
            // and the data-directory lock. destroy() normally ends tor
            // at once; the timeout only bounds the pathological case.
            // (waitFor-with-timeout/destroyForcibly need API 26; older
            // devices keep the previous destroy-only behavior.)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val exited = runCatching {
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                }.getOrDefault(false)
                if (!exited) runCatching { p.destroyForcibly() }
            }
        }
    }

    private fun extractAsset(name: String, destDir: File) {
        val dest = File(destDir, name)
        if (dest.exists() && dest.length() > 0) return
        // Copy to a temp file and rename it over the destination, so an
        // interrupted copy never leaves a truncated file behind that
        // the length check above would then accept forever.
        val tmp = File(destDir, "$name.tmp")
        context.assets.open("tor/$name").use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        const val TAG = "Tor"

        val BOOTSTRAP_PCT = Regex("""Bootstrapped (\d{1,3})%""")

        val DEFAULT_FAKE_SNI = listOf(
            "play.googleapis.com", "drive.google.com", "cdn.ampproject.org", "api.github.com",
            "ajax.aspnetcdn.com", "verizon.com", "eset.com",
        )
    }
}
