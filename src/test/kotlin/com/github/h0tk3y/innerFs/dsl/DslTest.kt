package com.github.h0tk3y.innerFs.dsl

import com.github.h0tk3y.innerFs.InnerPath
import com.github.h0tk3y.innerFs.tempInnerFileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
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

    @Test fun workingWithExistingFs() {
        tempInnerFileSystem().use { ifs ->
            val dir = ifs / "a" / "b" / "c"
            Files.createDirectories(dir)
            Files.newByteChannel(dir / "a.txt", CREATE, WRITE).use { channel ->
                channel.write(ByteBuffer.wrap("Hello".toByteArray()))
            }

            // now open it with DSL
            innerFs(ifs.underlyingPath) {
                directory("a") {
                    directory("b") {
                        directory("c") {
                            file("a.txt") {
                                assertEquals("Hello".toByteArray().size, size().toInt())
                                position(size())
                                write(ByteBuffer.wrap(", world!".toByteArray()))
                            }
                        }
                    }
                }

                file(here / "a" / "b" / "c" / "a.txt") {
                    assertEquals("Hello, world!".toByteArray().size, size().toInt())
                    position(size())
                    write(ByteBuffer.wrap(" Hello, world!".toByteArray()))
                }

                directory("a") {
                    file(here / "b" / "c" / "a.txt") {
                        assertEquals("Hello, world! Hello, world!".toByteArray().size, size().toInt())
                    }
                }
            }
        }
    }
}