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
 */
object AetherBinary {

    const val SOCKS_PORT = 1819

    /** Path of the binary in the app's native library folder. */
    fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libaether.so")

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
     * Starts the installed binary with [args] in [workDir]. Stdout and
     * stderr both go to workDir/aether.log, truncated on every start.
     * Call [ensureInstalled] first; this throws if the binary is missing.
     */
    fun start(context: Context, args: List<String>, workDir: File): Process {
        val binary = ensureInstalled(context).getOrThrow()
        workDir.mkdirs()
        val log = File(workDir, "aether.log")
        return ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(log))
            .start()
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
