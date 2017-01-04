
import InnerFileSystem.CreateMode.CREATE_OR_OPEN
import InnerFileSystem.CreateMode.FAIL_IF_NOT_EXISTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

/**
 * Created by igushs on 12/29/16.
 */

@ExtendWith(IfsExternalResource::class)
class FilesTest {
    @InjectIfs
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
        val newFs = InnerFileSystemProvider.instance.newFileSystem(ifs.underlyingPath, emptyMap<String, Any>())

        val reopenedAfterClose = newFs.openFile(newFs.getPath("/a.txt"), true, true, false, FAIL_IF_NOT_EXISTS)
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
            ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = FAIL_IF_NOT_EXISTS, append = false)
        }
    }

    @Test fun concurrentDelete() {
        val f = ifs.openFile(ifs.getPath("/a.txt"), read = true, write = true, create = CREATE_OR_OPEN, append = false)

        assertThrows<FileSystemException> {
            ifs.deleteFile(ifs.getPath("/a.txt"))
        }

        f.close()

        Files.delete(ifs.getPath("/a.txt"))
    }
}