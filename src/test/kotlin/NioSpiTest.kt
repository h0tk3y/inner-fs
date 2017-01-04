import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView

/**
 * Created by igushs on 1/4/17.
 */

@ExtendWith(IfsExternalResource::class)
class NioSpiTest {
    @InjectIfs
    lateinit var ifs: InnerFileSystem

    @InjectIfs
    lateinit var anotherIfs: InnerFileSystem

    @Test fun exists() {
        val file = ifs.openFile(ifs.getPath("/abc.txt"))
        file.write(ByteBuffer.allocate(123))
        file.close()

        Assertions.assertTrue(Files.exists(ifs.getPath("/abc.txt")))

        ifs.deleteFile(ifs.getPath("/abc.txt"))

        Assertions.assertFalse(Files.exists(ifs.getPath("/abc.txt")))
    }

    @Test fun moveSameDirectory() {
        val path = ifs.getPath("/abc.txt")
        val file = ifs.openFile(path)
        file.write(ByteBuffer.allocate(123))
        file.close()
        Assertions.assertTrue(Files.exists(path))

        val targetPath = path.resolveSibling("def.txt")
        Files.move(path, targetPath)
        Assertions.assertFalse(Files.exists(path))
        Assertions.assertTrue(Files.exists(targetPath))
        Assertions.assertEquals(123L, Files.getFileAttributeView(targetPath, BasicFileAttributeView::class.java).readAttributes().size())
    }

    @Test fun moveDifferentDirectoriesWithData() {
        val dataSize = 12345
        Files.createDirectories(ifs.getPath("/a"))
        Files.createDirectories(ifs.getPath("/b"))

        val path = ifs.getPath("/a/abc.txt")
        val channel = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        channel.write(ByteBuffer.allocate(dataSize).apply {
            (1..dataSize).forEach { put(it.toByte()) }
            position(0)
        })
        channel.close()

        val targetPath = path.resolveSibling("../b/def.txt")
        Files.move(path, targetPath, StandardCopyOption.ATOMIC_MOVE)

        val targetChannel = Files.newByteChannel(targetPath, StandardOpenOption.READ)
        val bytes = ByteBuffer.allocate(dataSize)
        targetChannel.read(bytes)
        bytes.position(0)
        (1..dataSize).forEach { Assertions.assertEquals(it.toByte(), bytes.get()) }
    }

    @Test fun moveDifferentFileSystemsWithData() {
        val dataSize = 12345
        Files.createDirectories(ifs.getPath("/a"))
        Files.createDirectories(anotherIfs.getPath("/b"))

        val path = ifs.getPath("/a/abc.txt")
        val channel = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        channel.write(ByteBuffer.allocate(dataSize).apply {
            (1..dataSize).forEach { put(it.toByte()) }
            position(0)
        })
        channel.close()

        val targetPath = anotherIfs.getPath("/b/def.txt")
        Files.move(path, targetPath, StandardCopyOption.ATOMIC_MOVE)

        val targetChannel = Files.newByteChannel(targetPath, StandardOpenOption.READ)
        val bytes = ByteBuffer.allocate(dataSize)
        targetChannel.read(bytes)
        bytes.position(0)
        (1..dataSize).forEach { Assertions.assertEquals(it.toByte(), bytes.get()) }
    }
}