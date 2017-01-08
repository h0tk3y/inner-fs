package com.github.h0tk3y.innerFs

internal data class Located<T>(val location: Long,
                               val value: T)

internal val Located<DirectoryEntry>.entry get() = value