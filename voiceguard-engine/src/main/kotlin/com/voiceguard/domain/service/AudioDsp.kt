package com.voiceguard.domain.service

import kotlin.math.sqrt

/** Root-mean-square of [samples]. Returns 0 for an empty array. */
internal fun computeRms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    val sumSq = samples.sumOf { it.toDouble() * it.toDouble() }
    return sqrt(sumSq / samples.size).toFloat()
}
