package com.voiceguard.harness

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("RuleDiscrimination — per-rule separability statistics")
class RuleDiscriminationTest {

    private val aiHigh = listOf(0.8f, 0.9f, 1.0f)
    private val humanLow = listOf(0.0f, 0.1f, 0.2f)

    // ── AUC ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AUC is 1.0 when every AI score is above every HUMAN score")
    fun auc_perfect_separation() {
        assertEquals(1.0f, RuleDiscrimination.auc(aiHigh, humanLow), absoluteTolerance = 1e-6f)
    }

    @Test
    @DisplayName("AUC is 0.0 when the direction is fully inverted")
    fun auc_inverted() {
        assertEquals(0.0f, RuleDiscrimination.auc(humanLow, aiHigh), absoluteTolerance = 1e-6f)
    }

    @Test
    @DisplayName("AUC is 0.5 when both classes have identical scores (all ties)")
    fun auc_no_separation_all_ties() {
        val tied = listOf(0.5f, 0.5f)
        assertEquals(0.5f, RuleDiscrimination.auc(tied, tied), absoluteTolerance = 1e-6f)
    }

    @Test
    @DisplayName("AUC handles partial overlap with ties to the analytic value")
    fun auc_partial_overlap() {
        // ai={0.4,0.6}, human={0.4,0.2}. Pairs (ai vs human):
        // 0.4>0.2 ✓, 0.4=0.4 ½, 0.6>0.2 ✓, 0.6>0.4 ✓ → (1+0.5+1+1)/4 = 0.875
        val auc = RuleDiscrimination.auc(listOf(0.4f, 0.6f), listOf(0.4f, 0.2f))
        assertEquals(0.875f, auc, absoluteTolerance = 1e-6f)
    }

    @Test
    @DisplayName("AUC is NaN when a class is empty")
    fun auc_empty_class() {
        assertTrue(RuleDiscrimination.auc(emptyList(), humanLow).isNaN())
    }

    // ── Cohen's d ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cohen's d is large and positive when AI scores are well above HUMAN")
    fun cohens_d_positive() {
        // means .9 vs .1, each sd = .1 → pooled .1 → d = 8.0
        assertEquals(8.0f, RuleDiscrimination.cohensD(aiHigh, humanLow), absoluteTolerance = 1e-3f)
    }

    @Test
    @DisplayName("Cohen's d is negative when the rule's direction is inverted")
    fun cohens_d_negative() {
        assertTrue(RuleDiscrimination.cohensD(humanLow, aiHigh) < 0f)
    }

    @Test
    @DisplayName("Cohen's d is NaN with fewer than two samples in a group")
    fun cohens_d_too_few_samples() {
        assertTrue(RuleDiscrimination.cohensD(listOf(0.9f), humanLow).isNaN())
    }

    // ── bestThreshold ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bestThreshold separates cleanly-separable classes at 100% accuracy")
    fun best_threshold_separable() {
        val (threshold, accuracy) = RuleDiscrimination.bestThreshold(aiHigh, humanLow)
        assertEquals(1.0f, accuracy, absoluteTolerance = 1e-6f)
        assertTrue(threshold > 0.2f && threshold < 0.8f, "cutoff should land in the empty gap, was $threshold")
    }

    @Test
    @DisplayName("bestThreshold caps below 100% when classes overlap")
    fun best_threshold_overlap() {
        // One AI file (0.1) sits inside the human cluster → at most 3/4 correct.
        val (_, accuracy) = RuleDiscrimination.bestThreshold(listOf(0.1f, 0.9f), listOf(0.0f, 0.2f))
        assertEquals(0.75f, accuracy, absoluteTolerance = 1e-6f)
    }

    // ── mean / std ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sampleStd uses the n-1 denominator and is NaN below two samples")
    fun sample_std() {
        assertEquals(0.1f, RuleDiscrimination.sampleStd(humanLow), absoluteTolerance = 1e-6f)
        assertTrue(RuleDiscrimination.sampleStd(listOf(0.5f)).isNaN())
    }
}
