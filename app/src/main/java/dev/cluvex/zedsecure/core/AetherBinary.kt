package dev.cluvex.zedsecure.core

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates and runs the Aether engine binary.
 *
 * The binary is not part of the source tree: tools/get-cores.sh fetches it
 * at build time into app/src/main/jniLibs/<abi>/libaether.so, and Android
 * extracts the device's ABI copy into the app's native library folder at
 * install time.
 *
 * It must live there, not in the app's private files folder: Android
 * refuses to execute a file the app itself copies into private storage
 * (error=13, Permission denied, seen on device 2026-10-25), while a file
 * shipped as a native library may be executed from the lib folder. The
 * binary is never loaded as a JNI library, only started as a subprocess.
 * See AETHER-NOTICE.md for the licensing consequence of bundling it.
 * Chained profiles also need the Psiphon console client beside it
 * (libpsiphon-tunnel-core.so, from the same release tarball); the core
 * is pointed at it with AETHER_PSIPHON_BIN when it is present.
 */
object AetherBinary {

    const val SOCKS_PORT = 1819

    /** Path of the binary in the app's native library folder. */
    fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libaether.so")

    /** Path of the Psiphon console client in the app's native library folder, when shipped. */
    fun psiphonBinaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libpsiphon-tunnel-core.so")

    /**
     * Returns the Aether binary from the app's native library folder.
     * No copy is needed: the system extracts the right ABI's libaether.so
     * there at install time. Never throws: failures come back as
     * Result.failure with a message.
     */
    fun ensureInstalled(context: Context): Result<File> = synchronized(this) { runCatching {
        val lib = binaryFile(context)
        if (!lib.exists()) {
            throw IllegalStateException(
                "Aether binary (libaether.so) is missing from the app's native libraries",
            )
        }
        if (!lib.canExecute()) {
            throw IllegalStateException("Aether binary is present but cannot be executed")
        }
        lib
    } }

    /**
     * Working directory for the Aether process (configs, logs, identity).
     * Only created, never cleaned: identity files inside it must survive
     * across runs, because re-registering a device is rate-limited upstream.
     */
    fun configDir(context: Context): File =
        File(context.filesDir, "aether").apply { mkdirs() }

    /**
     * Working directory for manual-editor scans: separate from
     * [configDir], because starting a process truncates its own
     * aether.log, and a scan must never wipe the live session's log.
     * Also only created, never cleaned: a scan in this directory
     * registers its own identity once and reuses it afterwards.
     */
    fun scanDir(context: Context): File =
        File(context.filesDir, "aether-scan").apply { mkdirs() }

    /**
     * Last few lines of the binary's own log (stdout+stderr go there),
     * cleaned for display: blank lines dropped, ANSI colors stripped,
     * lines capped so a log tail stays readable in an error report.
     */
    fun logTail(workDir: File, maxLines: Int = 6): String {
        val lines = runCatching {
            java.io.File(workDir, "aether.log").readLines()
        }.getOrDefault(emptyList())
        val ansi = Regex("\u001B\\[[0-9;]*m")
        return lines.filter { it.isNotBlank() }.takeLast(maxLines)
            .joinToString("\n") { ansi.replace(it, "").take(120) }
    }

    /**
     * Starts the installed binary with [args] in [workDir]. Stdout and
     * stderr both go to workDir/aether.log, truncated on every start.
     * Call [ensureInstalled] first; this throws if the binary is missing.
     */
    fun start(context: Context, args: List<String>, workDir: File): Process {
        val binary = ensureInstalled(context).getOrThrow()
        workDir.mkdirs()
        val log = File(workDir, "aether.log")
        val builder = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
        // Chained profiles (--psiphon) make the core start the Psiphon
        // console client as a program of its own; it looks for it in
        // AETHER_PSIPHON_BIN first. The client ships beside the core as
        // libpsiphon-tunnel-core.so (tools/get-cores.sh); without it the
        // core dies at startup (seen on device 2026-10-27, exit=1).
        val psiphon = psiphonBinaryFile(context)
        if (psiphon.exists() && psiphon.canExecute()) {
            builder.environment()["AETHER_PSIPHON_BIN"] = psiphon.absolutePath
        }
        return builder.start()
    }

    /**
     * True when the binary's own log (stdout+stderr) already contains
     * [needle]. Used for log-line readiness: the core announces
     * "tunnel validated" once the tunnel carries traffic, and
     * "psiphon is ready" once a chained tunnel is up — both earlier and
     * more precise than polling the port and then probing HTTPS. The
     * log is truncated on every start, so a hit always belongs to the
     * current run.
     */
    fun logContains(workDir: File, needle: String): Boolean {
        val log = File(workDir, "aether.log")
        if (!log.exists()) return false
        return runCatching {
            log.useLines { lines -> lines.any { it.contains(needle) } }
        }.getOrDefault(false)
    }

    /**
     * Kills leftover Aether/Psiphon processes from earlier sessions that
     * are still holding the SOCKS port or the Psiphon datastore. A
     * crashed or force-stopped session can leave them behind (the
     * process outlives the app), and the next start then fails to bind
     * or to lock its datastore. Only processes of this app's own user
     * whose command line names one of the shipped libraries are killed,
     * and never this process itself. Fully guarded: any failure here
     * must not stop a session from starting.
     */
    fun killOrphans(context: Context) {
        runCatching {
            val myPid = android.os.Process.myPid()
            val myUid = android.os.Process.myUid()
            val markers = listOf("libaether.so", "libpsiphon-tunnel-core.so")
            val procDir = File("/proc")
            val entries = procDir.listFiles() ?: return@runCatching
            for (entry in entries) {
                val pid = entry.name.toIntOrNull() ?: continue
                if (pid == myPid) continue
                val statusUid = runCatching {
                    File(entry, "status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("Uid:") }
                            ?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
                    }
                }.getOrNull() ?: continue
                if (statusUid != myUid) continue
                val cmdline = runCatching {
                    File(entry, "cmdline").readBytes()
                        .toString(Charsets.UTF_8)
                }.getOrDefault("")
                if (markers.none { cmdline.contains(it) }) continue
                runCatching { android.os.Process.killProcess(pid) }
            }
        }
    }

    /** Stops [process]: destroy, wait up to 3 seconds, then force-kill. */
    fun stop(process: Process?) {
        if (process == null) return
        runCatching { process.destroy() }
        // waitFor-with-timeout and destroyForcibly need API 26; older
        // devices keep the destroy-only behavior.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val exited = runCatching {
                process.waitFor(3, TimeUnit.SECONDS)
            }.getOrDefault(false)
            if (!exited) runCatching { process.destroyForcibly() }
        }
    }
}
