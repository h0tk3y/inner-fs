package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.Assertions

internal inline fun <reified T : Throwable> assertThrows(noinline f: () -> Unit): T =
    Assertions.assertThrows<T>(T::class.java, f)