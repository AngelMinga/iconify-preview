package com.angelminga.iconifypreview

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Closest icon names to a misspelled one, for the "replace with" quick fixes. No IntelliJ dependencies. */
object IconSuggestions {

    fun similar(name: String, candidates: Collection<String>, limit: Int = 3): List<String> {
        val maxDistance = max(2, name.length / 3)
        return candidates.asSequence()
            .filter { abs(it.length - name.length) <= maxDistance }
            .map { it to distance(name, it) }
            .filter { it.second <= maxDistance }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Edit distance counting a swap of two neighbors as one edit, so `hoem` is 1 away from `home`. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
