package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.TestExtensionContext
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.reflect.KMutableProperty
import kotlin.reflect.memberProperties

/**
 * Created by igushs on 1/2/17.
 */

internal class IfsExternalResource : BeforeEachCallback, AfterEachCallback {
    private fun ifsProperties(context: TestExtensionContext) = context.testInstance.javaClass.kotlin.memberProperties
            .filterIsInstance<KMutableProperty<*>>()
            .filter { it.isLateinit && it.returnType.classifier == InnerFileSystem::class }
            .filterIsInstance<KMutableProperty<*>>()


    override fun beforeEach(context: TestExtensionContext) = ifsProperties(context).forEach {
        val ifs = tempInnerFileSystem()
        it.setter.call(context.testInstance, ifs)
    }

    override fun afterEach(context: TestExtensionContext) = ifsProperties(context).forEach {
        val ifs = it.getter.call(context.testInstance) as InnerFileSystem
        ifs.close()
        Files.deleteIfExists(ifs.underlyingPath)
    }
}

private val random = Random()
private fun tempInnerFileSystem() = InnerFileSystemProvider().newFileSystem(Paths.get("./test-inner-fs-${random.nextLong()}.ifs"))
