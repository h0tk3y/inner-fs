package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.dsl.div
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.*
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@ExtendWith(IfsExternalResource::class)
class ConcurrencyStressTest {
    lateinit var ifs: InnerFileSystem

    @Test fun fileAppend() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val pieceSize = BLOCK_SIZE * 3
        val nThreads = 3
        val piecesCountPerThread = 100
        val finishedLatch = CountDownLatch(nThreads)

        val channel = Files.newByteChannel(ifs / "a.txt", CREATE, WRITE, APPEND)

        repeat(nThreads) { threadId ->
            thread {
                val bytes = ByteBuffer.wrap(ByteArray(pieceSize) { threadId.toByte() })
                for (i in 1..piecesCountPerThread) {
                    bytes.position(0)
                    channel.write(bytes)
                }
                finishedLatch.countDown()
            }
        }

        finishedLatch.await()
        channel.close()

        val readChannel = Files.newByteChannel(ifs / "a.txt", READ)
        val buffer = ByteBuffer.allocate(pieceSize)
        val counts = mutableMapOf<Int, Int>()
        for (i in 1..nThreads * piecesCountPerThread) {
            buffer.position(0)
            readChannel.read(buffer)
            Assertions.assertTrue(buffer.array().distinct().size == 1)
            val threadId = buffer.array()[0]
            Assertions.assertTrue(threadId in 0..nThreads - 1)
            counts.merge(threadId.toInt(), 1, Int::plus)
        }
        Assertions.assertEquals((0..nThreads - 1).associate { it to piecesCountPerThread }, counts)
    }

    @Test fun createAndDeleteEntries() = assertTimeoutPreemptively(Duration.ofSeconds(10)) {
        val nThreadsCreate = 3
        val nThreadsDelete = 3
        val filesToCreatePerThread = 50
        val filesToDeletePerThread = 30
        val finishLatch = CountDownLatch(nThreadsCreate + nThreadsDelete)

        val files = ArrayBlockingQueue<String>(nThreadsCreate * filesToCreatePerThread)

        repeat(nThreadsCreate) { threadId ->
            thread {
                repeat(filesToCreatePerThread) { n ->
                    val fileName = "/thread-$threadId-file-$n"
                    Files.createFile(ifs / fileName)
                    files.put(fileName)
                }
                finishLatch.countDown()
            }
        }
        repeat(nThreadsDelete) {
            thread {
                repeat(filesToDeletePerThread) {
                    val fileName = files.take()
                    Files.delete(ifs / fileName)
                }
                finishLatch.countDown()
            }
        }

        finishLatch.await()
        val filesSet = files.toSet()
        val existingFiles = Files.newDirectoryStream(ifs.rootDirectories.single()).map { "/" + (it as InnerPath).fileNameString }.toSet()
        Assertions.assertEquals(filesSet, existingFiles)
    }

    @Test fun pingPong() {
        val path1 = ifs / "a.txt"
        val path2 = ifs / "b.txt"
        Files.createFile(path1)

        val iterations = 1000

        val finishLatch = CountDownLatch(2)
        val otherErrorsHappened = AtomicBoolean()

        fun movingThread(from: Path, to: Path, count: AtomicInteger) = thread {
            try {
                repeat(iterations) {
                    try {
                        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
                        count.incrementAndGet()
                    } catch (_: NoSuchFileException) {
                    }
                }
                finishLatch.countDown()
            } catch (t: Throwable) {
                otherErrorsHappened.set(true)
                throw t
            }
        }

        val count1 = AtomicInteger()
        val count2 = AtomicInteger()

        movingThread(path1, path2, count1)
        movingThread(path2, path1, count2)

        finishLatch.await()

        assertFalse(otherErrorsHappened.get())

        val parity = count1.get() + count2.get()
        val fileThatShouldExist = if (parity % 2 == 0) path1 else path2
        assertTrue(Files.exists(fileThatShouldExist))
    }
}