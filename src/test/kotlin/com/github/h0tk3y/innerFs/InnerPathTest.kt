package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException

@ExtendWith(IfsExternalResource::class)
class InnerPathTest {
    lateinit var ifs: InnerFileSystem

    @Test fun subpath() {
        val path = ifs.getPath("/abc/def/ghi/1.txt")
        val subPath = path.subpath(1, 3)
        Assertions.assertEquals((subPath as InnerPath).pathSegments, path.pathSegments.subList(1, 3))
    }

    @Test fun getName() {
        val path = ifs.getPath("/abc/def/ghi/1.txt")
        for (i in 0..path.nameCount - 1) {
            Assertions.assertEquals(InnerPath(ifs, listOf(path.pathSegments[i])), path.getName(i))
        }
    }

    @Test fun toReal() {
        val path = ifs.getPath("/abc/def/ghi/1.txt")
        assertThrows<NoSuchFileException> { path.toRealPath() }

        Files.createDirectories(path.parent)
        Files.createFile(path)
        Assertions.assertEquals(path, path.toRealPath())
    }

    @Test fun nonIfsPath() {
        val nonIfsPath = FileSystems.getDefault().rootDirectories.first()
        Assertions.assertEquals(
                nonIfsPath,
                assertThrows<IncompatiblePathException> {
                    ifs.rootDirectories.single().resolve(nonIfsPath)
                }.otherPath
        )
    }

    @Test fun emptyPath() {
        val path = ifs.getPath("")
        Assertions.assertFalse(path.isAbsolute)
        Assertions.assertEquals(emptyList<String>(), path.pathSegments)
    }

    @Test fun startsWithEndsWith() {
        val ifsPath = ifs.getPath("/a/b/c/defgh/1.txt")
        Assertions.assertTrue(ifsPath.startsWith(ifs.getPath("/a/b/c")))
        Assertions.assertFalse(ifsPath.startsWith(ifs.getPath("/a/b/c/de")))
        Assertions.assertTrue(ifsPath.endsWith(ifs.getPath("defgh/1.txt")))
        Assertions.assertFalse(ifsPath.endsWith(ifs.getPath("gh/1.txt")))
    }
}