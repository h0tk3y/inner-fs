package com.github.h0tk3y.innerFs

/**
 * Created by igushs on 12/21/16.
 */

const val MAX_NAME_SIZE = 256

internal object NameChecker {
    fun isValidName(segment: String) = segment.toByteArray(Charsets.UTF_8).size <= MAX_NAME_SIZE
}