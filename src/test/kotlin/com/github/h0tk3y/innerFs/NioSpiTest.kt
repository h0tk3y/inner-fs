package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.dsl.div
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.BasicFileAttributeView

@ExtendWith(IfsExternalResource::class)
class NioSpiTest {
    lateinit var ifs: InnerFileSystem

    lateinit var anotherIfs: InnerFileSystem

    @Test fun exists() {
        val file = ifs.openFile(ifs.getPath("/abc.txt"))
        file.write(ByteBuffer.allocate(123))
        file.close()

        assertTrue(Files.exists(ifs.getPath("/abc.txt")))

        ifs.deleteFile(ifs.getPath("/abc.txt"))

        assertFalse(Files.exists(ifs.getPath("/abc.txt")))
    }

    @Test fun moveSameDirectory() {
        val path = ifs.getPath("/abc.txt")
        val file = ifs.openFile(path)
        file.write(ByteBuffer.allocate(123))
        file.close()
        assertTrue(Files.exists(path))

        val targetPath = path.resolveSibling("def.txt")
        Files.move(path, targetPath)
        assertFalse(Files.exists(path))
        assertTrue(Files.exists(targetPath))
        assertEquals(123L, Files.getFileAttributeView(targetPath, BasicFileAttributeView::class.java).readAttributes().size())
    }

    @Test fun moveDifferentDirectoriesWithData() {
        val dataSize = 12345
        Files.createDirectories(ifs.getPath("/a"))
        Files.createDirectories(ifs.getPath("/b"))

        val path = ifs.getPath("/a/abc.txt")
        val channel = Files.newByteChannel(path, CREATE_NEW, WRITE)
        channel.write(ByteBuffer.allocate(dataSize).apply {
            (1..dataSize).forEach { put(it.toByte()) }
            position(0)
        })
        channel.close()

        val targetPath = path.resolveSibling("../b/def.txt")
        Files.move(path, targetPath, StandardCopyOption.ATOMIC_MOVE)

        val targetChannel = Files.newByteChannel(targetPath, READ)
        val bytes = ByteBuffer.allocate(dataSize)
        targetChannel.read(bytes)
        bytes.position(0)
        (1..dataSize).forEach { assertEquals(it.toByte(), bytes.get()) }
    }

    @Test fun moveInAndOut() {
        val path = ifs.getPath("/a/b/c/abc.txt")
        val anotherPath = ifs.getPath("/a/abc.txt")

        Files.createDirectories(path.parent)
        Files.createFile(path)
        assertTrue(Files.exists(path))
        assertFalse(Files.exists(anotherPath))

        Files.move(path, anotherPath)
        assertFalse(Files.exists(path))
        assertTrue(Files.exists(anotherPath))

        Files.move(anotherPath, path)
        assertTrue(Files.exists(path))
        assertFalse(Files.exists(anotherPath))
    }

    @Test fun moveDifferentFileSystemsWithData() {
        val dataSize = 12345
        Files.createDirectories(ifs.getPath("/a"))
        Files.createDirectories(anotherIfs.getPath("/b"))

        val path = ifs.getPath("/a/abc.txt")
        val channel = Files.newByteChannel(path, CREATE_NEW, WRITE)
        channel.write(ByteBuffer.allocate(dataSize).apply {
            (1..dataSize).forEach { put(it.toByte()) }
            position(0)
        })
        channel.close()

        val targetPath = anotherIfs.getPath("/b/def.txt")
        Files.move(path, targetPath, StandardCopyOption.ATOMIC_MOVE)

        val targetChannel = Files.newByteChannel(targetPath, READ)
        val bytes = ByteBuffer.allocate(dataSize)
        targetChannel.read(bytes)
        bytes.position(0)
        (1..dataSize).forEach { assertEquals(it.toByte(), bytes.get()) }
    }

    @Test fun fileAttributes() {
        val path = ifs.getPath("/abc.txt")
        val output = Files.newOutputStream(path)
        output.write("abc".toByteArray())
        output.close()
        val attrsView = Files.getFileAttributeView(path, BasicFileAttributeView::class.java)
        assertEquals("abc.txt", attrsView.name())
        val attrs = attrsView.readAttributes()
        assertEquals("abc".toByteArray().size.toLong(), attrs.size())
        assertTrue(attrs.isRegularFile)
        assertFalse(attrs.isDirectory)
    }

    @Test fun fileStore() {
        val dataSize = 10240

        Files.newByteChannel(ifs / "a.txt", CREATE, WRITE).use { file -> file.write(ByteBuffer.allocate(dataSize)) }
        val fileStore = ifs.fileStores.single()
        assertEquals(ifs.isReadOnly, fileStore.isReadOnly)
        assertTrue(fileStore.totalSpace >= dataSize)
        assertTrue(0 <= fileStore.usableSpace && fileStore.usableSpace <= fileStore.totalSpace)
        assertEquals(0, fileStore.unallocatedSpace)
        Files.delete(ifs / "a.txt")
        assertTrue(fileStore.unallocatedSpace >= dataSize)
    }
}