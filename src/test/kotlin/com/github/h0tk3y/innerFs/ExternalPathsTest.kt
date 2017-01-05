package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.spi.FileSystemProvider

@ExtendWith(IfsExternalResource::class)
class ExternalPathsTest {

    lateinit var ifs: InnerFileSystem

    @Test fun installedProvider() {
        val providers = FileSystemProvider.installedProviders()
        Assertions.assertTrue(providers.any { it is InnerFileSystemProvider })
    }

    @Test fun externalPathResolving() {
        ifs.openFile(ifs.getPath("/a.txt")).close()
        val ifsUri = ifs.underlyingPath.toUri()
        val externalPath = Paths.get(URI.create("ifs:$ifsUri!/a.txt"))
        Assertions.assertTrue(Files.exists(externalPath))
    }
}