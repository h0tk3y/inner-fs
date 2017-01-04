package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.TestExtensionContext
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.reflect.KMutableProperty
import kotlin.reflect.memberProperties

/**
 * Created by igushs on 1/2/17.
 */

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class InjectIfs

internal class IfsExternalResource : BeforeEachCallback, AfterEachCallback {
    private fun ifsProperties(context: TestExtensionContext) = context.testInstance.javaClass.kotlin.memberProperties
            .filterIsInstance<KMutableProperty<*>>()
            .filter { it.annotations.any { it is InjectIfs } && it.returnType.classifier == InnerFileSystem::class }
            .filterIsInstance<KMutableProperty<*>>()


    override fun beforeEach(context: TestExtensionContext) = ifsProperties(context).forEach {
        val file = Files.createTempFile(Paths.get("."), "innerFs", ".ifs")
        Files.delete(file)
        val ifs = InnerFileSystemProvider().newFileSystem(file, emptyMap<String, Unit>())
        it.setter.call(context.testInstance, ifs)
    }

    override fun afterEach(context: TestExtensionContext) = ifsProperties(context).forEach {
        val ifs = it.getter.call(context.testInstance) as InnerFileSystem
        ifs.close()
        Files.deleteIfExists(ifs.underlyingPath)
    }
}
