package dev.cluvex.zedsecure.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Byte counters of the SSTP tunnel.
 *
 * The vendored engine owns the TUN file itself, so it is the only place that
 * sees the packets; it bumps these and SstpBridge turns the deltas into the
 * status the home screen draws. Nothing else reads them.
 */
object SstpTraffic {
    /** Bytes the tunnel delivered to the phone (download). */
    val down = AtomicLong(0L)

    /** Bytes the phone pushed into the tunnel (upload). */
    val up = AtomicLong(0L)

    fun reset() {
        down.set(0L)
        up.set(0L)
    }
}
