package app.kept.ui

import java.math.BigDecimal
import java.math.RoundingMode

fun furthestSetupStep(mainWallet: String?, tradingWallet: String?): Int = when {
    mainWallet == null -> 1
    tradingWallet == null -> 2
    else -> 3
}

fun boundedSetupStep(requested: Int, mainWallet: String?, tradingWallet: String?): Int =
    requested.coerceIn(1, furthestSetupStep(mainWallet, tradingWallet))

/** Exact SOL-to-lamports parser. Floating point is not safe for transaction amounts. */
fun solInputToLamports(input: String): Long? {
    val value = input.trim()
    if (!value.matches(Regex("^[0-9]+(?:\\.[0-9]{1,9})?$"))) return null
    return runCatching {
        BigDecimal(value).movePointRight(9).longValueExact().takeIf { it > 0 }
    }.getOrNull()
}

/** Exact percentage calculation for token base units, bounded to the available balance. */
fun percentageOf(balance: Long, input: String): Long? {
    if (balance <= 0) return null
    val value = input.trim()
    if (!value.matches(Regex("^[0-9]+(?:\\.[0-9]{1,9})?$"))) return null
    return runCatching {
        val percent = BigDecimal(value)
        require(percent > BigDecimal.ZERO && percent <= BigDecimal("100"))
        BigDecimal.valueOf(balance)
            .multiply(percent)
            .divide(BigDecimal("100"), 0, RoundingMode.DOWN)
            .longValueExact()
            .takeIf { it > 0 && it <= balance }
    }.getOrNull()
}
