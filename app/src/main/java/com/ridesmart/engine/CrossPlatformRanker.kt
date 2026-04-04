package com.ridesmart.engine

import com.ridesmart.model.RideResult
import com.ridesmart.model.Signal
import kotlin.math.abs

object CrossPlatformRanker {

    private const val SCORE_GAP_THRESHOLD = 5.0

    enum class Tier { SIGNAL, SCORE, EFFICIENCY }

    data class RankingOutput(
        val ranked: List<RideResult>,
        val winner: RideResult,
        val runnerUp: RideResult?,
        val profitDelta: Double,
        val efficiencyDelta: Double,
        val tierWon: Tier,
        val summaryLine: String
    )

    private fun signalRank(signal: Signal) = when (signal) {
        Signal.GREEN  -> 3
        Signal.YELLOW -> 2
        Signal.RED    -> 1
    }

    private val comparator = Comparator<RideResult> { a, b ->
        val sigDiff = signalRank(b.signal) - signalRank(a.signal)
        if (sigDiff != 0) return@Comparator sigDiff
        val scoreDiff = a.decisionScore - b.decisionScore
        if (abs(scoreDiff) >= SCORE_GAP_THRESHOLD)
            return@Comparator if (scoreDiff > 0) -1 else 1
        val effDiff = a.efficiencyPerKm - b.efficiencyPerKm
        if (effDiff > 0) -1 else if (effDiff < 0) 1 else 0
    }

    private fun buildSummaryLine(
        winner: RideResult, runnerUp: RideResult,
        profitDelta: Double, efficiencyDelta: Double, tier: Tier
    ): String {
        val wp = winner.parsedRide.platform.uppercase()
        val rp = runnerUp.parsedRide.platform.uppercase()
        return when (tier) {
            Tier.SIGNAL     ->
                "★ $wp  ${winner.signal.name} vs ${runnerUp.signal.name} $rp"
            Tier.SCORE      ->
                "★ $wp  S:${winner.decisionScore.toInt()} vs S:${runnerUp.decisionScore.toInt()} $rp"
            Tier.EFFICIENCY -> {
                val delta = if (profitDelta >= 0) "(+₹${profitDelta.toInt()})"
                            else "(-₹${(-profitDelta).toInt()})"
                "★ $wp  ₹${"%.1f".format(winner.efficiencyPerKm)}/km vs ₹${"%.1f".format(runnerUp.efficiencyPerKm)}/km $rp $delta"
            }
        }
    }

    fun rank(results: List<RideResult>): RankingOutput {
        require(results.isNotEmpty())
        if (results.size == 1) {
            val only = results[0]
            return RankingOutput(results, only, null, 0.0, 0.0, Tier.SIGNAL,
                "★ ${only.parsedRide.platform.uppercase()}")
        }
        val sorted   = results.sortedWith(comparator)
        val winner   = sorted[0]
        val runnerUp = sorted[1]
        val tier = when {
            signalRank(winner.signal) != signalRank(runnerUp.signal) -> Tier.SIGNAL
            abs(winner.decisionScore - runnerUp.decisionScore) >= SCORE_GAP_THRESHOLD -> Tier.SCORE
            else -> Tier.EFFICIENCY
        }
        val profitDelta     = winner.netProfit - runnerUp.netProfit
        val efficiencyDelta = winner.efficiencyPerKm - runnerUp.efficiencyPerKm
        return RankingOutput(sorted, winner, runnerUp, profitDelta, efficiencyDelta,
            tier, buildSummaryLine(winner, runnerUp, profitDelta, efficiencyDelta, tier))
    }
}
