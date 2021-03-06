package com.github.h0tk3y.innerFs

/**
 * Maintains counters associated with [K] items and handles first entry of an item (i.e. when its counter switches
 * from 0 to 1) with the [onEnter] function and *exit* of the last item (i.e. when the counter switches from 1 to 0)
 * with the [onExit] function.
 */
internal class EnterExitCounterMap<K>(
        val onEnter: (K) -> Unit,
        val onExit: (K) -> Unit) {

    private val map = HashMap<K, Int>()

    @Synchronized
    fun increase(key: K) {
        map.compute(key) { _, value ->
            if (value == null) {
                onEnter(key)
                1
            } else value + 1
        }
    }

    @Synchronized
    fun decrease(key: K) {
        map.computeIfPresent(key) { _, value ->
            val result = value - 1
            if (result == 0) {
                onExit(key)
                null
            } else result
        }
    }
}