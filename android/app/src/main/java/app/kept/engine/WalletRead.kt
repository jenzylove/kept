package app.kept.engine

import java.math.BigInteger

/**
 * A plain read of a wallet's trading record, for deciding whether it is worth following. Every
 * figure comes straight from the wallet's priced sales; there is no score and nothing is weighted.
 */
data class WalletRead(
    /** Won minus given back, in lamports. Negative when the wallet lost overall. */
    val net: BigInteger,
    val wins: Int,
    val losses: Int,
    /** Winning sales out of every priced sale, 0 to 100. */
    val winPercent: Int,
    /** How much of what it won it handed back on losing sales, 0 to 100 and beyond. Null when it won nothing. */
    val gaveBackPercent: Int?,
    val typicalWin: BigInteger,
    val typicalLoss: BigInteger,
    val biggestLoss: BigInteger,
    /** Sales that could not be priced, out of all sales, 0 to 100. */
    val unpricedPercent: Int,
    val verdict: Verdict,
    /** True when enough is missing that the read should be taken as a partial picture. */
    val partial: Boolean,
) {
    enum class Verdict(val line: String, val lean: String) {
        KeepsMost("Wins, and keeps most of it.", "keeps most of it."),
        AheadButLeaky("Ahead overall, but gives a lot back.", "gives a lot back."),
        GivesItBack("Wins, then gives it all back.", "gives it all back."),
        NoWins("No winning sale in this window.", "No winning sale"),
        Nothing("No sales we could price.", "price."),
    }

    companion object {
        /** Giving back more than this share of winnings counts as leaky. */
        const val LEAKY_PERCENT = 35
        /** With more than this share of sales unpriced, the read is a partial picture. */
        const val PARTIAL_PERCENT = 25

        fun of(m: Engine.Mirror, truncated: Boolean = false): WalletRead {
            val priced = m.wins.size + m.losses.size
            val net = m.won - m.givenBack
            val hundred = BigInteger.valueOf(100)
            val gaveBack = if (m.won.signum() > 0) m.givenBack.multiply(hundred).divide(m.won).toInt() else null
            val unpriced = if (priced + m.unpriced > 0) m.unpriced * 100 / (priced + m.unpriced) else 0
            val verdict = when {
                priced == 0 -> Verdict.Nothing
                m.wins.isEmpty() -> Verdict.NoWins
                net.signum() <= 0 -> Verdict.GivesItBack
                gaveBack != null && gaveBack > LEAKY_PERCENT -> Verdict.AheadButLeaky
                else -> Verdict.KeepsMost
            }
            return WalletRead(
                net = net,
                wins = m.wins.size,
                losses = m.losses.size,
                winPercent = if (priced > 0) m.wins.size * 100 / priced else 0,
                gaveBackPercent = gaveBack,
                typicalWin = median(m.wins.map { it.profit }),
                typicalLoss = median(m.losses.map { it.profit.abs() }),
                biggestLoss = m.losses.maxOfOrNull { it.profit.abs() } ?: BigInteger.ZERO,
                unpricedPercent = unpriced,
                verdict = verdict,
                partial = truncated || unpriced > PARTIAL_PERCENT,
            )
        }

        /** The middle value, so one huge trade does not pass for the usual one. */
        private fun median(values: List<BigInteger>): BigInteger {
            if (values.isEmpty()) return BigInteger.ZERO
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]).shiftRight(1)
        }
    }
}
