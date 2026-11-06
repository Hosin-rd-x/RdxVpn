package dev.cluvex.zedsecure.foxy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class FastlyChallengeError(message: String) : Exception(message)

private const val TAG = "FastlyChallengeSolver"

private const val SOLVE_TIMEOUT_SECONDS = 60L

private const val MAX_POST_BACK_ROUNDS = 3

private const val POW_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private const val HTML_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

private val CHALLENGE_PREFIX_REGEX = Regex("/_fs-ch-[A-Za-z0-9]+")
private val INIT_CALL_REGEX = Regex("init\\((\\[[^\\]]*\\]),\\s*\"([^\"]+)\",\\s*\"([^\"]+)\"")

class FastlyChallengeSolver(private val cookieJar: SimpleCookieJar) {

    private class NoChallengePage : Exception()

    /**
     * Solves the Fastly challenge and installs the clearance cookies into the
     * shared jar. When [targetHost] is given (the host of the request that
     * just got 406), that host is solved FIRST: the clearance is bound to the
     * host that was solved, so solving api and stopping there left oauth
     * unsolved and the oauth retry kept 406ing. The other FxA hosts are
     * still tried as a fallback. With no target host the old order is kept
     * (used by the Guardian client).
     */
    suspend fun solveAndInstall(targetHost: String? = null) = withContext(Dispatchers.IO) {
        // Pick up any cookies a real browser already earned (see WebViewChallengeSolver).
        val synced = runCatching { cookieJar.syncFromWebView() }.getOrDefault(0)
        AppLogger.i(TAG, "HTTP solver started (target host ${targetHost ?: "auto"}, synced $synced WebView cookies first)")
        val orderedHosts = buildList {
            if (targetHost != null) add("https://$targetHost")
            add("https://api.accounts.firefox.com")
            add("https://accounts.firefox.com")
            add("https://oauth.accounts.firefox.com")
        }.distinct()
        var lastError: Exception? = null
        for (base in orderedHosts) {
            AppLogger.i(TAG, "HTTP solver trying host $base")
            try {
                solveOnHost(base)
                AppLogger.i(TAG, "HTTP solver succeeded on host $base")
                return@withContext
            } catch (_: NoChallengePage) {
                AppLogger.i(TAG, "host $base: no challenge page, trying next host")
                continue
            } catch (e: FastlyChallengeError) {
                AppLogger.w(TAG, "HTTP solver failed on host $base", e)
                lastError = e
            }
        }
        throw lastError ?: FastlyChallengeError("host did not serve a Fastly challenge page")
    }

    private fun baseOrigin(prefixUrl: String): String {
        val idx = prefixUrl.indexOf("/_fs-ch-")
        return if (idx > 0) prefixUrl.substring(0, idx) else prefixUrl
    }

    private fun solvePow(base: String, targetHex: String): String? {
        val target = try {
            targetHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return null
        }
        if (target.size != 32) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (a in POW_ALPHABET) {
            for (b in POW_ALPHABET) {
                val suffix = "$a$b"
                val hash = digest.digest((base + suffix).toByteArray(Charsets.UTF_8))
                if (hash.contentEquals(target)) return suffix
            }
        }
        return null
    }

    private fun fetchText(
        client: OkHttpClient,
        url: String,
        accept: String = HTML_ACCEPT,
    ): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", CHROME_DESKTOP_USER_AGENT)
            .header("Accept", accept)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw FastlyChallengeError("GET $url returned HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun fetchChallengePage(client: OkHttpClient, pageUrl: String): Pair<String, Boolean> {
        val body = fetchText(client, pageUrl)
        val isChallenge = body.contains("/_fs-ch-") && body.contains("Client Challenge")
        return body to isChallenge
    }

    private fun parseChallengeInit(script: String): Pair<JSONArray, String> {
        val matches = INIT_CALL_REGEX.findAll(script).toList()
        if (matches.isEmpty()) throw FastlyChallengeError("challenge init() call not found in script")
        val last = matches.last()
        val challenges = try {
            JSONArray(last.groupValues[1])
        } catch (e: Exception) {
            throw FastlyChallengeError("could not parse challenge list: ${e.message}")
        }
        if (challenges.length() == 0) throw FastlyChallengeError("empty challenge list")
        return challenges to last.groupValues[2]
    }

    private fun fetchPat(client: OkHttpClient, prefixUrl: String, token: String): String {
        val patUrl = "$prefixUrl/pat?token=${URLEncoder.encode(token, "UTF-8")}"
        val request = Request.Builder()
            .url(patUrl)
            .header("Accept", "text/plain")
            .header("Content-Type", "application/json")
            .header("User-Agent", CHROME_DESKTOP_USER_AGENT)
            .header("Origin", baseOrigin(prefixUrl))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 401) return ""
            if (!response.isSuccessful) throw FastlyChallengeError("PAT request returned HTTP ${response.code}")
            val json = try {
                JSONObject(response.body?.string().orEmpty())
            } catch (e: Exception) {
                throw FastlyChallengeError("could not parse PAT response: ${e.message}")
            }
            val auth = json.optString("auth", "")
            if (auth.isEmpty()) throw FastlyChallengeError("empty PAT auth token")
            return auth
        }
    }

    private fun clientMetricsAnswer(): JSONObject = JSONObject().apply {
        put("ty", "clientmetrics")
        put("webdriver", false)
        put(
            "bot_detection_result",
            JSONObject().apply {
                put("bot_detected", false)
                put("bot_kind", JSONObject.NULL)
            },
        )
        put(
            "browser_metrics",
            JSONObject().apply {
                put("client_data", "{}")
                put("error_trace", JSONObject.NULL)
            },
        )
        put("detector_results", JSONObject())
        put("v", 2)
    }

    private fun answerChallenge(client: OkHttpClient, prefixUrl: String, token: String, challenge: JSONObject): JSONObject {
        val data = challenge.optJSONObject("data") ?: JSONObject()
        return when (val type = challenge.optString("ty")) {
            "pow" -> {
                val base = data.optString("base", "")
                val answer = solvePow(base, data.optString("hash", ""))
                    ?: throw FastlyChallengeError("no proof-of-work solution found for base $base")
                JSONObject().apply {
                    put("ty", "pow")
                    put("base", base)
                    put("answer", answer)
                    put("hmac", data.optString("hmac", ""))
                    put("expires", data.optString("expires", ""))
                }
            }
            "pat" -> JSONObject().apply {
                put("ty", "pat")
                put("auth", fetchPat(client, prefixUrl, token))
            }
            "clientmetrics" -> clientMetricsAnswer()
            else -> throw FastlyChallengeError("unsupported Fastly challenge type '$type' (captcha cannot be solved automatically)")
        }
    }

    private fun installChallengeCookies(solverJar: SimpleCookieJar) {
        val cookies = solverJar.snapshotAll()
        if (cookies.isEmpty()) throw FastlyChallengeError("challenge completed but no cookies were issued")
        AppLogger.i(TAG, "installing ${cookies.size} challenge cookies")
        for (cookie in cookies) {
            cookieJar.set(cookie.name, cookie.value, "firefox.com")
        }
    }

    private fun solveOnHost(base: String) {
        val solverJar = SimpleCookieJar()
        val solver = ControlPlaneHttp.client.newBuilder()
            .cookieJar(solverJar)
            .connectTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        val pageUrl = "$base/"
        val (page, isChallenge) = fetchChallengePage(solver, pageUrl)
        AppLogger.i(TAG, "host $base: page fetched (${page.length} bytes), isChallenge=$isChallenge")
        if (!isChallenge) throw NoChallengePage()

        val prefixMatch = CHALLENGE_PREFIX_REGEX.find(page)
            ?: throw FastlyChallengeError("challenge asset prefix not found on $base")
        val prefixUrl = base + prefixMatch.value
        AppLogger.i(TAG, "host $base: challenge prefix ${prefixMatch.value}")

        val script = fetchText(solver, "$prefixUrl/script.js?reload=true")
        AppLogger.i(TAG, "host $base: script.js fetched (${script.length} bytes)")
        var (challenges, token) = parseChallengeInit(script)
        AppLogger.i(TAG, "host $base: ${challenges.length()} challenges to answer")

        repeat(MAX_POST_BACK_ROUNDS) { round ->
            val types = (0 until challenges.length()).map { challenges.getJSONObject(it).optString("ty") }
            AppLogger.i(TAG, "host $base: post-back round ${round + 1}, answering types=$types")
            val answers = JSONArray()
            for (i in 0 until challenges.length()) {
                answers.put(answerChallenge(solver, prefixUrl, token, challenges.getJSONObject(i)))
            }
            val postBody = JSONObject().apply {
                put("token", token)
                put("data", answers)
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$prefixUrl/fst-post-back")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", CHROME_DESKTOP_USER_AGENT)
                .header("Origin", baseOrigin(prefixUrl))
                .post(postBody)
                .build()
            solver.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw FastlyChallengeError("challenge post-back returned HTTP ${response.code}")
                val body = JSONObject(response.body?.string().orEmpty())
                AppLogger.i(TAG, "host $base: post-back status=${body.optString("status")}")
                if (body.optString("status") == "success") {
                    val (_, stillChallenged) = fetchChallengePage(solver, pageUrl)
                    AppLogger.i(TAG, "host $base: verification page stillChallenged=$stillChallenged")
                    if (stillChallenged) throw FastlyChallengeError("challenge cookie not accepted on this exit IP")
                    installChallengeCookies(solverJar)
                    return
                }
                val nextChallenges = body.optJSONArray("ch")
                val nextToken = body.optString("tok", "")
                if (nextChallenges == null || nextChallenges.length() == 0 || nextToken.isEmpty()) {
                    throw FastlyChallengeError("unexpected post-back response: $body")
                }
                AppLogger.i(TAG, "host $base: next round has ${nextChallenges.length()} challenges")
                challenges = nextChallenges
                token = nextToken
            }
        }
        throw FastlyChallengeError("Fastly challenge did not complete within $MAX_POST_BACK_ROUNDS rounds")
    }
}
