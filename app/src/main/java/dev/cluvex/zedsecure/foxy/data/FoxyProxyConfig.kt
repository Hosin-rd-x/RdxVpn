package dev.cluvex.zedsecure.foxy.data

/**
 * Server-side Foxy refresh proxy (added in 3.8.18).
 *
 * The phone's exit IP is blocked by Mozilla's bot protection (bare HTTP 406
 * on the token-refresh POST, even after the challenge solvers run), while the
 * same request from our own server reaches Mozilla's app logic. So when the
 * direct refresh fails with 406, FxaAuthRepository falls back to POSTing the
 * identical refresh body here; the server forwards it to the FIXED FxA
 * OAuth token endpoint and returns Mozilla's response verbatim.
 *
 * NOTE: this secret is compiled into a public-repo APK, so it is only a
 * light deterrent against casual abuse. The real control is the server-side
 * rate limit (10 req/min per IP, 120 req/min global) in
 * /home/ubuntu/foxy-proxy/foxy_refresh_server.py. Rotate the server secret
 * and this value together if it is ever abused.
 */
object FoxyProxyConfig {
    const val REFRESH_URL = "https://foxy-refresh.hosinrdx.ir/refresh"
    const val SECRET_HEADER = "X-Foxy-Proxy-Secret"
    const val SECRET = "YSqm_8t4BU70qjUwa7cD9oHoTktWzAAmcMigdfybccc"
}
