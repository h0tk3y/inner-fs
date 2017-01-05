package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.dsl.div
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Created by igushs on 1/5/17.
 */

@ExtendWith(IfsExternalResource::class)
class RecursiveInnerFileSystemsTest {
    lateinit var ifs: InnerFileSystem

    @Test fun recursiveInnerFs() {
        val dataSize = 10240
        val data = ByteArray(dataSize, Int::toByte)

        val innerPath = ifs / "inner.ifs"
        val another = InnerFileSystemProvider().newFileSystem(innerPath)
        Files.createDirectories(another / "a")
        val file = Files.newByteChannel(another / "a" / "1.txt", CREATE, WRITE)
        file.write(ByteBuffer.wrap(data))
        file.close()

        Assertions.assertTrue(Files.size(innerPath) > 0)
        Assertions.assertEquals(dataSize.toLong(), Files.size(another / "a" / "1.txt"))
        val read = Files.readAllBytes(another / "a" / "1.txt")
        Assertions.assertArrayEquals(data, read)

        val pathByUrl = Paths.get(URI.create("ifs:ifs:${ifs.underlyingPath.toUri()}!/inner.ifs!/a/1.txt"))
        Assertions.assertTrue(Files.exists(pathByUrl))
    }
}