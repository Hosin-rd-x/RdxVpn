package dev.cluvex.zedsecure.core

import android.net.ipsec.ike.ChildSaProposal
import android.net.ipsec.ike.IkeSaProposal
import android.net.ipsec.ike.SaProposal
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * IKEv2 proposals.
 *
 * Two rules of the platform drive the shape of every list in here.
 *
 *  * RFC 7296 allows at most one transform of each type inside a single
 *    proposal, and the Android IKE library keeps the same rule while it
 *    negotiates: the accepted suite has to carry exactly one transform per
 *    type. Alternatives therefore travel as separate proposals.
 *  * Combined mode ciphers (AES-GCM, ChaCha20-Poly1305) must not be mixed
 *    with the integrity algorithms of the non combined mode and they carry
 *    no integrity algorithm at all.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object Ikev2Proposals {
    private const val MAX_PROPOSALS = 8

    private val ENCRYPTION: Map<String, Pair<Int, Int>> = mapOf(
        "aes128" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CBC to 128),
        "aes192" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CBC to 192),
        "aes256" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CBC to 256),
        "aes128ctr" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CTR to 128),
        "aes192ctr" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CTR to 192),
        "aes256ctr" to (SaProposal.ENCRYPTION_ALGORITHM_AES_CTR to 256),
        "aes128gcm128" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16 to 128),
        "aes256gcm128" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16 to 256),
        "aes128gcm16" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16 to 128),
        "aes256gcm16" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16 to 256),
        "aes128gcm12" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12 to 128),
        "aes256gcm12" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12 to 256),
        "aes128gcm8" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8 to 128),
        "aes256gcm8" to (SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8 to 256),
        "chacha20poly1305" to (SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305 to 256),
    )

    private val INTEGRITY: Map<String, Int> = mapOf(
        "sha1" to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96,
        "sha" to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96,
        "sha256" to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
        "sha384" to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192,
        "sha512" to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
        "aesxcbc" to SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96,
        "aescmac" to SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96,
    )

    private val PRF: Map<String, Int> = mapOf(
        "prfsha1" to SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1,
        "prfsha256" to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
        "prfsha384" to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384,
        "prfsha512" to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512,
        "prfaesxcbc" to SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC,
        "prfaescmac" to SaProposal.PSEUDORANDOM_FUNCTION_AES128_CMAC,
    )

    private val DH: Map<String, Int> = mapOf(
        "modp1024" to SaProposal.DH_GROUP_1024_BIT_MODP,
        "modp1536" to SaProposal.DH_GROUP_1536_BIT_MODP,
        "modp2048" to SaProposal.DH_GROUP_2048_BIT_MODP,
        "modp3072" to SaProposal.DH_GROUP_3072_BIT_MODP,
        "modp4096" to SaProposal.DH_GROUP_4096_BIT_MODP,
        "curve25519" to SaProposal.DH_GROUP_CURVE_25519,
    )

    private val PRF_FOR_INTEGRITY: Map<Int, Int> = mapOf(
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96 to SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1,
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128 to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192 to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384,
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256 to SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512,
        SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96 to SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC,
        SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96 to SaProposal.PSEUDORANDOM_FUNCTION_AES128_CMAC,
    )

    private val INTEGRITY_FOR_PRF: Map<Int, Int> = mapOf(
        SaProposal.PSEUDORANDOM_FUNCTION_HMAC_SHA1 to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA1_96,
        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256 to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_384 to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_384_192,
        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512 to SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
        SaProposal.PSEUDORANDOM_FUNCTION_AES128_XCBC to SaProposal.INTEGRITY_ALGORITHM_AES_XCBC_96,
        SaProposal.PSEUDORANDOM_FUNCTION_AES128_CMAC to SaProposal.INTEGRITY_ALGORITHM_AES_CMAC_96,
    )

    private val AEAD_ENCRYPTIONS = setOf(
        SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_8,
        SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_12,
        SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16,
        SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305,
    )

    private fun tokens(spec: String): List<String> =
        spec.lowercase().split('-', '_', ' ', ',').map { it.trim() }.filter { it.isNotEmpty() }

    fun isParsable(spec: String, child: Boolean): Boolean =
        spec.isBlank() || tokens(spec).all { classify(it, child) != null }

    private fun classify(token: String, child: Boolean): String? = when {
        ENCRYPTION.containsKey(token) -> "enc"
        INTEGRITY.containsKey(token) -> "int"
        DH.containsKey(token) -> "dh"
        !child && PRF.containsKey(token) -> "prf"
        child && token == "noesn" -> "ignore"
        else -> null
    }

    private fun isAead(algorithm: Int): Boolean = algorithm in AEAD_ENCRYPTIONS

    /**
     * One IKE proposal: exactly one encryption, one integrity (only for the
     * non combined mode), one PRF and one DH group.
     */
    private fun ikeProposal(
        encryption: Int,
        keyLength: Int,
        integrity: Int?,
        prf: Int,
        dh: Int,
    ): IkeSaProposal {
        val builder = IkeSaProposal.Builder()
            .addEncryptionAlgorithm(encryption, keyLength)
        if (integrity != null) builder.addIntegrityAlgorithm(integrity)
        builder.addPseudorandomFunction(prf)
        builder.addDhGroup(dh)
        return builder.build()
    }

    /**
     * One ESP proposal: exactly one encryption and, for the non combined
     * mode, one integrity algorithm.
     */
    private fun childProposal(
        encryption: Int,
        keyLength: Int,
        integrity: Int?,
    ): ChildSaProposal {
        val builder = ChildSaProposal.Builder()
            .addEncryptionAlgorithm(encryption, keyLength)
        if (integrity != null) builder.addIntegrityAlgorithm(integrity)
        return builder.build()
    }

    fun defaultIke(): IkeSaProposal = ikeProposal(
        SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256,
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
        SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512,
        SaProposal.DH_GROUP_2048_BIT_MODP,
    )

    fun defaultChild(): ChildSaProposal = childProposal(
        SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256,
        SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
    )

    /**
     * IKE (phase 1) proposals.
     *
     * Every entry carries a single transform per type, the alternatives are
     * separate proposals. Combined mode ciphers come without an integrity
     * algorithm, that pairing is rejected by the platform.
     */
    fun defaultIkeVariants(): List<IkeSaProposal> = listOf(
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_512,
            SaProposal.DH_GROUP_CURVE_25519,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 128,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, 256, null,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, 256, null,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_CURVE_25519,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, 128, null,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, 256, null,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
        ikeProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CTR, 256,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
            SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256,
            SaProposal.DH_GROUP_2048_BIT_MODP,
        ),
    )

    /**
     * ESP (phase 2) proposals.
     *
     * One transform per type, the alternatives are separate proposals.
     */
    fun defaultChildVariants(): List<ChildSaProposal> = listOf(
        childProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 256,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_512_256,
        ),
        childProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CBC, 128,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
        ),
        childProposal(
            SaProposal.ENCRYPTION_ALGORITHM_AES_CTR, 256,
            SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128,
        ),
        childProposal(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, 256, null),
        childProposal(SaProposal.ENCRYPTION_ALGORITHM_AES_GCM_16, 128, null),
        childProposal(SaProposal.ENCRYPTION_ALGORITHM_CHACHA20_POLY1305, 256, null),
    )

    /**
     * IKE proposals for a user supplied specification.
     *
     * A specification that names several ciphers turns into several
     * proposals, because one proposal may only carry one transform per type.
     * The returned list is empty when the specification cannot be served.
     */
    fun parseIke(spec: String): List<IkeSaProposal> {
        if (spec.isBlank()) return emptyList()
        val encryptions = mutableListOf<Pair<Int, Int>>()
        val integrities = mutableListOf<Int>()
        val prfs = mutableListOf<Int>()
        val dhs = mutableListOf<Int>()
        for (t in tokens(spec)) {
            when {
                ENCRYPTION.containsKey(t) -> encryptions += ENCRYPTION.getValue(t)
                INTEGRITY.containsKey(t) -> integrities += INTEGRITY.getValue(t)
                DH.containsKey(t) -> dhs += DH.getValue(t)
                PRF.containsKey(t) -> prfs += PRF.getValue(t)
                else -> return emptyList()
            }
        }
        if (encryptions.isEmpty()) return emptyList()
        val dh = dhs.firstOrNull() ?: SaProposal.DH_GROUP_2048_BIT_MODP
        val out = mutableListOf<IkeSaProposal>()
        for ((alg, len) in encryptions.distinct()) {
            val aead = isAead(alg)
            val choices: List<Int?> = if (aead) {
                listOf(null)
            } else {
                integrities.ifEmpty { listOf(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128) }
                    .distinct()
                    .map { it as Int? }
            }
            for (integrity in choices) {
                val prf = prfs.firstOrNull()
                    ?: integrity?.let { PRF_FOR_INTEGRITY[it] }
                    ?: SaProposal.PSEUDORANDOM_FUNCTION_SHA2_256
                val proposal = runCatching {
                    ikeProposal(alg, len, integrity, prf, dh)
                }.getOrNull() ?: continue
                out += proposal
                if (out.size >= MAX_PROPOSALS) return out
            }
        }
        return out
    }

    /**
     * ESP proposals for a user supplied specification.
     *
     * A combined mode cipher never gets an integrity algorithm, the platform
     * rejects that combination.
     */
    fun parseChild(spec: String): List<ChildSaProposal> {
        if (spec.isBlank()) return emptyList()
        val encryptions = mutableListOf<Pair<Int, Int>>()
        val integrities = mutableListOf<Int>()
        for (t in tokens(spec)) {
            when {
                ENCRYPTION.containsKey(t) -> encryptions += ENCRYPTION.getValue(t)
                INTEGRITY.containsKey(t) -> integrities += INTEGRITY.getValue(t)
                DH.containsKey(t) -> Unit
                t == "noesn" -> Unit
                else -> return emptyList()
            }
        }
        if (encryptions.isEmpty()) return emptyList()
        val out = mutableListOf<ChildSaProposal>()
        for ((alg, len) in encryptions.distinct()) {
            val aead = isAead(alg)
            val choices: List<Int?> = if (aead) {
                listOf(null)
            } else {
                integrities.ifEmpty { listOf(SaProposal.INTEGRITY_ALGORITHM_HMAC_SHA2_256_128) }
                    .distinct()
                    .map { it as Int? }
            }
            for (integrity in choices) {
                val proposal = runCatching {
                    childProposal(alg, len, integrity)
                }.getOrNull() ?: continue
                out += proposal
                if (out.size >= MAX_PROPOSALS) return out
            }
        }
        return out
    }
}
