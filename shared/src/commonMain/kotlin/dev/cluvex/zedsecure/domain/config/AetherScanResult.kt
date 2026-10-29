package dev.cluvex.zedsecure.domain.config

/**
 * What a manual-editor scan found: the gateway the core selected, plus
 * the inner hop when the scan was a two-hop one (gool / MIM) and the
 * core announced both. [innerGateway] is "" for single-hop scans and
 * when the core only announced one hop — an outer-only profile is
 * valid, the core picks the missing hop itself at connect time.
 */
data class AetherScanResult(
    val gateway: String,
    val innerGateway: String = "",
)
