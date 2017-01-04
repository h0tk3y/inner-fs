package com.github.h0tk3y.innerFs

/**
 * Created by igushs on 12/25/16.
 */

internal data class Located<T>(val location: Long,
                               val value: T)

internal val Located<DirectoryEntry>.entry get() = value