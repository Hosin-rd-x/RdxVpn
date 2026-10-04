package dev.cluvex.zedsecure.foxy

import android.util.Base64

/**
 * Embedded Firefox-VPN (Foxy) account credentials for silent sign-in.
 *
 * The refresh token is stored obfuscated (base64 over XOR with a constant key) so a
 * casual strings(1) dump of the APK does not reveal it. It is NOT encryption; anyone
 * determined can recover it, which is why this APK is distributed privately.
 */
object FoxyCredentials {

    private const val KEY = "RdxVpn/Foxy/2026"

    // base64(XOR(refresh_token, KEY))
    private const val OBFUSCATED_REFRESH_TOKEN =
        "ZVFIYkYIGycOTRofBQUDVzABTWMRW0xwXxpATAIIAwQ2VR01Ew0eIgtNHRpTUQYHMVJKMhFcSXAMSRtLCgdUBg=="

    fun refreshToken(): String = runCatching {
        val raw = Base64.decode(OBFUSCATED_REFRESH_TOKEN, Base64.NO_WRAP)
        val keyBytes = KEY.toByteArray(Charsets.UTF_8)
        String(raw.mapIndexed { i, b -> (b.toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }.toByteArray())
    }.getOrDefault("")
}
