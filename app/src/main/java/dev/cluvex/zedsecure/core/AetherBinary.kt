package dev.cluvex.zedsecure.core

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates, installs and runs the Aether engine binary.
 *
 * The binary is not part of the source tree: tools/get-cores.sh fetches it
 * at build time into app/src/main/assets/aether/<abi>/aether, and this
 * object copies it out of the APK assets into private app storage on first
 * use. See AETHER-NOTICE.md for the licensing consequence of bundling it.
 */
object AetherBinary {

    const val SOCKS_PORT = 1819

    private val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /** Path of the installed binary. Call [ensureInstalled] first. */
    fun binaryFile(context: Context): File =
        File(File(context.filesDir, "aether-bin"), "aether")

    /**
     * Copies assets/aether/<abi>/aether to filesDir/aether-bin/aether.
     * <abi> is the first of Build.SUPPORTED_ABIS that we ship a binary for.
     * The installed copy is only replaced when it is missing or its size
     * differs from the asset, so a reinstall does not needlessly rewrite it.
     * Never throws: failures come back as Result.failure with a message.
     */
    fun ensureInstalled(context: Context): Result<File> = synchronized(this) { runCatching {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS }
            ?: throw IllegalStateException(
                "No Aether binary for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})",
            )
        val dir = File(context.filesDir, "aether-bin").apply { mkdirs() }
        val dest = File(dir, "aether")
        // Copy to a temp file first, so an interrupted copy never leaves a
        // truncated binary behind that the size check would then accept.
        val tmp = File(dir, "aether.tmp")
        try {
            context.assets.open("aether/$abi/aether").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw IllegalStateException("Aether binary for $abi is missing from the app assets", e)
        }
        if (!dest.exists() || dest.length() != tmp.length()) {
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } else {
            tmp.delete()
        }
        if (!dest.setExecutable(true, false) && !dest.setExecutable(true)) {
            throw IllegalStateException("Could not make the Aether binary executable")
        }
        dest
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
