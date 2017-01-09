package com.github.h0tk3y.innerFs
import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.CREATE_OR_OPEN
import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.OPEN_OR_FAIL
import com.github.h0tk3y.innerFs.dsl.div
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

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
        ifs.delete(ifs.getPath("/a.txt"))

        assertThrows<NoSuchFileException> {
            ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = OPEN_OR_FAIL, append = false)
        }
    }

    @Test fun concurrentDelete() {
        val f = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)

        assertThrows<FileIsInUseException> {
            ifs.delete(ifs.getPath("/a.txt"))
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

    @Test fun transferFrom() {
        val dataSize = BLOCK_SIZE * 8 + 1
        val data = ByteBuffer.wrap(ByteArray(dataSize, Int::toByte))

        val channelFrom = FileChannel.open(ifs / "a.txt", CREATE, WRITE)
        channelFrom.write(data)
        channelFrom.position(0)

        val channelTo = FileChannel.open(ifs / "b.txt", CREATE, WRITE)
        channelTo.transferFrom(channelFrom, 0, channelFrom.size())

        val dataToCheck = ByteBuffer.allocate(dataSize)
        channelTo.read(dataToCheck, 0L)
        dataToCheck.position(0)
        for (i in 0..dataSize - 1)
            assertEquals(i.toByte(), dataToCheck.get())
    }

    @Test fun transferTo() {
        val dataSize = BLOCK_SIZE * 8 + 1
        val data = ByteBuffer.wrap(ByteArray(dataSize, Int::toByte))

        val channelFrom = FileChannel.open(ifs / "a.txt", CREATE, WRITE)
        channelFrom.write(data)

        val channelTo = FileChannel.open(ifs / "b.txt", CREATE, WRITE)
        channelFrom.transferTo(0, channelFrom.size(), channelTo)

        val dataToCheck = ByteBuffer.allocate(dataSize)
        channelTo.read(dataToCheck, 0L)
        dataToCheck.position(0)
        for (i in 0..dataSize - 1)
            assertEquals(i.toByte(), dataToCheck.get())
    }

    @Test fun transferSelf() {
        val dataSize = BLOCK_SIZE * 8 + 1
        val data = ByteBuffer.wrap(ByteArray(dataSize, Int::toByte))

        val channelFrom = FileChannel.open(ifs / "a.txt", CREATE, WRITE)
        channelFrom.write(data)

        channelFrom.transferTo(0, channelFrom.size(), channelFrom)

        val dataToCheck = ByteBuffer.allocate(dataSize * 2)
        channelFrom.position(0L)
        channelFrom.read(dataToCheck, 0L)
        dataToCheck.position(0)
        for (i in 0..dataSize - 1)
            assertEquals(i.toByte(), dataToCheck.get())
        for (i in 0..dataSize - 1)
            assertEquals(i.toByte(), dataToCheck.get())
    }

    @Test fun openDirectoryAsFile() {
        val path = ifs / "a" / "b" / "c"
        Files.createDirectories(path)
        assertThrows<NoSuchFileException> { ifs.openFile(path) }
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

    @Test fun hidden() {
        val f = ifs.getPath("/.nomedia")
        Files.createFile(f)
        Files.isHidden(f)
    }

    @Test fun time() {
        val path = ifs / "a" / "b.txt"

        val now0 = System.currentTimeMillis()
        Files.createDirectories(path.parent)
        Thread.sleep(100)

        val now1 = System.currentTimeMillis()
        val file = Files.newByteChannel(path, CREATE, WRITE)
        Thread.sleep(100)

        val now2 = System.currentTimeMillis()
        file.write(ByteBuffer.allocate(123))
        Thread.sleep(100)

        val now3 = System.currentTimeMillis()
        file.position(0).read(ByteBuffer.allocate(123))
        file.close()

        val dirAttrs = Files.readAttributes(path.parent, BasicFileAttributes::class.java)
        assertTrue(FileTime.fromMillis(now0) <= dirAttrs.creationTime() &&
                   dirAttrs.creationTime() <= FileTime.fromMillis(now1))
        assertEquals(FileTime.fromMillis(0L), dirAttrs.lastModifiedTime())
        assertEquals(FileTime.fromMillis(0L), dirAttrs.lastAccessTime())

        val fileAttrs = Files.readAttributes(path, BasicFileAttributes::class.java)
        assertTrue(fileAttrs.creationTime() >= FileTime.fromMillis(now1) &&
                   dirAttrs.creationTime() <= FileTime.fromMillis(now2))
        assertTrue(fileAttrs.lastModifiedTime() >= FileTime.fromMillis(now2) &&
                   dirAttrs.creationTime() <= FileTime.fromMillis(now3))
        assertTrue(fileAttrs.lastAccessTime() >= FileTime.fromMillis(now3) &&
                   dirAttrs.creationTime() <= FileTime.fromMillis(System.currentTimeMillis()))
    }

    @Test fun timeUpdate() {
        val file = ifs / "abc.txt"
        Files.createFile(file)
        val view = Files.getFileAttributeView(file, BasicFileAttributeView::class.java)
        view.setTimes(FileTime.fromMillis(123), FileTime.fromMillis(456), FileTime.fromMillis(789))
        val attrs = view.readAttributes()
        assertEquals(123L, attrs.lastModifiedTime().toMillis())
        assertEquals(456L, attrs.lastAccessTime().toMillis())
        assertEquals(789L, attrs.creationTime().toMillis())

        val newViewAttrs = Files.getFileAttributeView(file, BasicFileAttributeView::class.java).readAttributes()
        assertEquals(123L, newViewAttrs.lastModifiedTime().toMillis())
        assertEquals(456L, newViewAttrs.lastAccessTime().toMillis())
        assertEquals(789L, newViewAttrs.creationTime().toMillis())
    }
}