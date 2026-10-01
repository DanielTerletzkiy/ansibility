package de.terletzkiy.ansibility.vault

/**
 * The time source of the idle lock and the plaintext cache: milliseconds of a monotonic clock. Tests replace it with
 * a virtual clock, so the 30-minute idle lock and the 5-minute plaintext expiry are checked without waiting.
 */
fun interface VaultClock {
    fun millis(): Long

    companion object {
        /** `System.nanoTime()` in milliseconds: never jumps with the wall clock. */
        val SYSTEM: VaultClock = VaultClock { System.nanoTime() / 1_000_000 }
    }
}
