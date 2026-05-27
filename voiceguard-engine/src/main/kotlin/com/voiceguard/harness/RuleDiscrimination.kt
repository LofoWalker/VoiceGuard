package com.voiceguard.harness

import kotlin.math.sqrt

/**
 * Pure statistical helpers measuring how well one rule's suspicion score separates AI files
 * from HUMAN files on a labelled dataset.
 *
 * Every function operates on plain score lists with no dataset/IO dependency, so the
 * separability math is unit-testable without audio fixtures.
 *
 * Score contract: higher suspicion == more AI-like (see
 * [com.voiceguard.domain.port.AudioDetectionRule]). Metrics are oriented accordingly — a rule
 * whose suspicion is effectively inverted on the dataset shows AUC < 0.5 / negative Cohen's d.
 */
object RuleDiscrimination {

    fun mean(values: List<Float>): Float =
        if (values.isEmpty()) Float.NaN else values.average().toFloat()

    /** Sample standard deviation (n − 1 denominator). NaN for fewer than two values. */
    fun sampleStd(values: List<Float>): Float {
        if (values.size < 2) return Float.NaN
        val m = values.average()
        val ss = values.sumOf { (it - m) * (it - m) }
        return sqrt(ss / (values.size - 1)).toFloat()
    }

    /**
     * Cohen's d effect size: (mean_ai − mean_human) / pooledStd.
     *
     * Positive ⇒ AI scores higher (the rule's intended direction). NaN when either group has
     * fewer than two samples or the pooled standard deviation is zero.
     */
    fun cohensD(ai: List<Float>, human: List<Float>): Float {
        if (ai.size < 2 || human.size < 2) return Float.NaN
        val mAi = ai.average(); val mHu = human.average()
        val vAi = ai.sumOf { (it - mAi) * (it - mAi) } / (ai.size - 1)
        val vHu = human.sumOf { (it - mHu) * (it - mHu) } / (human.size - 1)
        val pooled = sqrt(((ai.size - 1) * vAi + (human.size - 1) * vHu) / (ai.size + human.size - 2))
        if (pooled == 0.0) return Float.NaN
        return ((mAi - mHu) / pooled).toFloat()
    }

    /**
     * Area under the ROC curve via the Mann–Whitney U statistic (rank-based, tie-aware).
     * Equals P(random AI score > random HUMAN score) + 0.5·P(tie).
     *
     * - 1.0  ⇒ perfect separation (every AI file scores above every HUMAN file)
     * - 0.5  ⇒ no discriminative power
     * - <0.5 ⇒ the rule's direction is inverted on this dataset
     *
     * NaN when either group is empty.
     */
    fun auc(ai: List<Float>, human: List<Float>): Float {
        if (ai.isEmpty() || human.isEmpty()) return Float.NaN

        val combined = (ai.map { it to true } + human.map { it to false })
            .sortedBy { it.first }

        // Fractional ranks (1-based), assigning tied scores their mean rank.
        val ranks = DoubleArray(combined.size)
        var i = 0
        while (i < combined.size) {
            var j = i
            while (j + 1 < combined.size && combined[j + 1].first == combined[i].first) j++
            val avgRank = (i + j) / 2.0 + 1.0 // mean of 1-based ranks (i+1)..(j+1)
            for (k in i..j) ranks[k] = avgRank
            i = j + 1
        }

        val rankSumAi = combined.indices.sumOf { if (combined[it].second) ranks[it] else 0.0 }
        val nAi = ai.size.toDouble(); val nHu = human.size.toDouble()
        val uAi = rankSumAi - nAi * (nAi + 1) / 2.0
        return (uAi / (nAi * nHu)).toFloat()
    }

    /**
     * Best accuracy-maximising cutoff under the decision rule "predict AI when suspicion ≥ t".
     *
     * Candidate thresholds are the midpoints between adjacent distinct scores plus the two
     * extremes, so the optimum over the empirical sample is always reachable. Returns
     * (threshold, accuracy). Returns (NaN, NaN) when either group is empty.
     */
    fun bestThreshold(ai: List<Float>, human: List<Float>): Pair<Float, Float> {
        if (ai.isEmpty() || human.isEmpty()) return Float.NaN to Float.NaN

        val distinct = (ai + human).distinct().sorted()
        val candidates = buildList {
            add(distinct.first() - 1e-4f)
            for (idx in 0 until distinct.size - 1) add((distinct[idx] + distinct[idx + 1]) / 2f)
            add(distinct.last() + 1e-4f)
        }

        val total = (ai.size + human.size).toFloat()
        var bestT = candidates.first(); var bestAcc = -1f
        for (t in candidates) {
            val correct = ai.count { it >= t } + human.count { it < t }
            val acc = correct / total
            if (acc > bestAcc) { bestAcc = acc; bestT = t }
        }
        return bestT to bestAcc
    }
}
