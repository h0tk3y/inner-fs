package com.github.h0tk3y.innerFs
import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.CREATE_OR_OPEN
import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.OPEN_OR_FAIL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Created by igushs on 12/29/16.
 */

@ExtendWith(IfsExternalResource::class)
class FilesTest {
    lateinit var ifs: InnerFileSystem

    @Test fun createAndReopen() {
        val channel = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        val bytes = ByteBuffer.allocate(10)
        for (b in 1..10)
            bytes.put(b.toByte())
        bytes.position(0)
        channel.write(bytes)

        val reopened = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        assertEquals(10, reopened.size())

        channel.close()
        reopened.close()

        val reopenedAgain = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        assertEquals(10, reopenedAgain.size())

        ifs.close()
        val newFs = InnerFileSystemProvider().newFileSystem(ifs.underlyingPath, emptyMap<String, Any>())

        val reopenedAfterClose = newFs.openFile(newFs.getPath("/a.txt"), true, true, false, false, OPEN_OR_FAIL)
        assertEquals(10, reopenedAfterClose.size())
    }

    @Test fun writeRead() {
        val dataSize = dataBytesInBlock * 10

        val bytes = ByteBuffer.allocate(dataSize)
        for (b in 1..dataSize) {
            bytes.put(b.toByte())
        }
        bytes.flip()

        val f = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        f.write(bytes)
        f.close()

        val g = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        bytes.position(0)
        g.read(bytes)
        bytes.position(0)
        for (b in 1..dataSize) {
            assertEquals(bytes.get(), b.toByte())
        }
        g.close()
    }

    @Test fun createAndDelete() {
        val f = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)
        val bytes = ByteBuffer.allocate(123)
        f.write(bytes)
        f.close()
        ifs.deleteFile(ifs.getPath("/a.txt"))

        assertThrows<NoSuchFileException> {
            ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = OPEN_OR_FAIL, append = false)
        }
    }

    @Test fun concurrentDelete() {
        val f = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)

        assertThrows<FileIsInUseException> {
            ifs.deleteFile(ifs.getPath("/a.txt"))
        }

        f.close()

        Files.delete(ifs.getPath("/a.txt"))
    }

    @Test fun appendMode() {
        val pieceSize = 1024

        val f = ifs.openFile(ifs.getPath("/a.txt"), write = true, append = true)
        val g = ifs.openFile(ifs.getPath("/a.txt"), write = true, append = true)
        for (i in 1..10) {
            f.write(ByteBuffer.wrap(ByteArray(pieceSize) { 0 }))
            g.write(ByteBuffer.wrap(ByteArray(pieceSize) { 1 }))
        }
        f.close()
        g.close()

        val h = ifs.openFile(ifs.getPath("/a.txt"), read = true)
        for (i in 1..10) {
            val zeros = ByteBuffer.allocate(pieceSize)
            h.read(zeros)
            assertTrue(zeros.array().all { it == 0.toByte() })
            val ones = ByteBuffer.allocate(pieceSize)
            h.read(ones)
            assertTrue(ones.array().all { it == 1.toByte() })
        }
    }

    @Test fun truncateExisting() {
        val f = ifs.openFile(ifs.getPath("/a.txt"))
        f.write(ByteBuffer.allocate(1024))
        f.close()
        val g = ifs.openFile(ifs.getPath("/a.txt"), truncateExisting = true)
        assertEquals(0, g.size())
        g.write(ByteBuffer.allocate(123))
        g.close()
        assertEquals(123, Files.size(ifs.getPath("/a.txt")))
    }
}