package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.dsl.div
import com.github.h0tk3y.innerFs.dsl.innerFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.*

/**
 * Created by igushs on 1/5/17.
 */

class DslTest {
    @Test fun dslTree() {
        var pathA: InnerPath? = null
        var pathB: InnerPath? = null
        var pathC: InnerPath? = null
        val ifs = innerFs("./test-inner-fs-${Random().nextLong()}.ifs") {
            directory("a") {
                directory("b") {
                    directory("c") {
                        pathA = file("a.txt") { }
                        pathB = file("b.txt") { write(ByteBuffer.allocate(123)) }
                    }
                }
                pathC = file("c.txt") { write(ByteBuffer.allocate(321)) }
                file("d.txt") { write(ByteBuffer.wrap("Hello".toByteArray())) }
            }
        }
        try { ifs.use {
            assertTrue(Files.exists(pathA))
            assertTrue(Files.exists(pathB))
            assertTrue(Files.exists(pathC))
            assertTrue(Files.exists(ifs.getPath("/a/d.txt")))

            assertTrue(Files.isDirectory(ifs.getPath("/a")))
            assertTrue(Files.isDirectory(ifs.getPath("/a/b")))
            assertTrue(Files.isDirectory(ifs.getPath("/a/b/c")))

            assertEquals(0L, Files.size(pathA))
            assertEquals(123, Files.size(pathB))
            assertEquals(321, Files.size(pathC))
            assertEquals("Hello".toByteArray().size.toLong(), Files.size(ifs.getPath("/a/d.txt")))

            val stringFromFile = String(Files.readAllBytes(ifs.getPath("/a/d.txt")))
            assertEquals("Hello", stringFromFile)
        } } finally {
            Files.delete(ifs.underlyingPath)
        }
    }

    @Test fun slashes() {
        val ifs = innerFs("1.ifs") { }
        try {
            ifs.use {
                val dirPath = ifs.getPath("/a/b/c/d")
                Files.createDirectories(dirPath)
                val filePath = dirPath.resolve("1.txt")
                Files.createFile(filePath)

                val slashDirPath = ifs / "a" / "b" / "c" / "d"
                assertEquals(dirPath, slashDirPath)
                assertTrue(Files.isDirectory(slashDirPath))

                val slashFilePaths = listOf(ifs / ifs.getPath("a/b/c/d/1.txt"),
                                            ifs / "a/b/c/d/1.txt",
                                            ifs / ifs.getPath("a/b/c/d/1.txt"),
                                            ifs / "a" / "b" / "c" / "d" / "1.txt",
                                            dirPath / "1.txt",
                                            slashDirPath / "1.txt")
                slashFilePaths.forEach { path ->
                    assertEquals(filePath, path)
                    assertTrue(Files.exists(path))
                }
            }
        } finally {
            Files.delete(ifs.underlyingPath)
        }
    }
}