package com.github.h0tk3y.innerFs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.*
import java.util.concurrent.atomic.AtomicLong

internal class BlocksFileChannel(val fileDescriptor: FileDescriptor,
                                 val readable: Boolean,
                                 val writable: Boolean,
                                 val append: Boolean) : FileChannel() {

    init {
        require(firstBlockLocation != BlockHeader.NO_NEXT_BLOCK)
    }

    private val firstBlockLocation get() = fileDescriptor.fileLocation
    private val innerFs get() = fileDescriptor.innerFs

    private var closed = false

    private fun checkNotClosed() {
        if (!fileDescriptor.innerFs.isOpen) throw IllegalStateException("The file system has been closed")
        if (closed) throw IllegalStateException("The channel has been closed")
    }

    private fun checkReadable() {
        checkNotClosed()
        if (!readable) throw NonReadableChannelException()
    }

    private fun checkWritable() {
        checkNotClosed()
        if (!writable) throw NonWritableChannelException()
    }

    private fun updateSize(newSize: Long) {
        if (newSize > fileDescriptor.size) {
            fileDescriptor.size = newSize
        }
    }

    // Atomic to make position changes from multiple readers thread-safe
    private val currentPosition: AtomicLong = AtomicLong(0L)

    private fun ensureLocation(offset: Long): Long {
        require(offset >= 0) { "Position in file should be non-negative" }
        var result: Long?
        do { // find a location to write or ensure the file length
            result = fileDescriptor.blockLocator.locate(offset)
            if (result == null)
                innerFs.allocateBlock(initializeDataBlock).apply {
                    innerFs.setBlockAsNext(fileDescriptor.blockLocator.lastBlockLocation, this)
                    fileDescriptor.blockLocator.appendBlock(this)
                }
        } while (result == null)
        return result
    }

    private fun writeBuffer(src: ByteBuffer, targetInFile: Long): Int {
        checkWritable()

        val buffer = src.slice()
        while (buffer.hasRemaining()) { // an iteration of this loop performs writes only within a single block
            val position = targetInFile + buffer.position()
            val location = ensureLocation(position)
            val bytesToWrite = Math.min(buffer.remaining(),
                                        fileDescriptor.blockLocator.remainingBytesInBlock(position))
            if (bytesToWrite == 0) {
                src.position(src.position() + buffer.position())
                return buffer.position()
            }
            val blockBuffer = buffer.slice().limit(bytesToWrite) as ByteBuffer
            try {
                while (blockBuffer.hasRemaining()) {
                    innerFs.underlyingChannel.write(blockBuffer, location + blockBuffer.position())
                    updateSize(targetInFile + buffer.position() + blockBuffer.position())
                }
            } catch (e: IOException) {
                throw IOException("Could not write to this file channel. Reason: $e", e)
            }
            buffer.position(buffer.position() + bytesToWrite)
        }
        src.position(src.position() + buffer.position())
        return buffer.position()
    }

    override fun write(src: ByteBuffer): Int {
        fileDescriptor.operation(write = true) {
            if (append)
                position(fileDescriptor.size)
            val result = writeBuffer(src, currentPosition.get())
            currentPosition.getAndAdd(result.toLong())
            return result
        }
    }

    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long {
        fileDescriptor.operation(write = true) {
            if (append)
                position(fileDescriptor.size)
            return srcs.drop(offset).take(length).sumByLong { write(it).toLong() }
        }
    }

    override fun write(src: ByteBuffer, position: Long): Int =
            fileDescriptor.operation(write = true) { writeBuffer(src, position) }

    override fun force(metaData: Boolean) {
        checkNotClosed()
        innerFs.underlyingChannel.force(metaData)
    }

    override fun implCloseChannel() {
        fileDescriptor.closeOne()
        closed = true
    }

    override fun truncate(size: Long): FileChannel {
        throw UnsupportedOperationException("Truncating an open file is not supported. Use open option TRUNCATE_EXISTING.")
    }

    override fun position(): Long {
        checkNotClosed()
        return currentPosition.get()
    }

    @Synchronized
    override fun position(newPosition: Long): FileChannel {
        checkNotClosed()
        currentPosition.set(newPosition)
        return this
    }

    override fun size(): Long {
        checkNotClosed()
        return fileDescriptor.size
    }

    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long {
        val intCount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return fileDescriptor.operation(write = target is BlocksFileChannel && target.fileDescriptor == fileDescriptor) {
            val buffer = ByteBuffer.allocate(intCount)
            read(buffer, position)
            buffer.flip()
            target.write(buffer).toLong()
        }
    }

    private fun readBuffer(dst: ByteBuffer, sourceInFile: Long): Int {
        checkReadable()

        val buffer = dst.slice()
        while (buffer.hasRemaining()) { // an iteration of this loop performs writes only within a single block
            val position = sourceInFile + buffer.position()
            val location = fileDescriptor.blockLocator.locate(position)!!
            val bytesToRead = buffer.remaining()
                    .coerceAtMost(fileDescriptor.blockLocator.remainingBytesInBlock(position))
                    .coerceAtMost((fileDescriptor.size - position).coerceAtMost(BLOCK_SIZE.toLong()).toInt()) //avoid overflow
            if (bytesToRead <= 0) {
                dst.position(dst.position() + buffer.position())
                return buffer.position()
            }
            val blockBuffer = buffer.slice().limit(bytesToRead) as ByteBuffer
            try {
                while (blockBuffer.hasRemaining()) {
                    innerFs.underlyingChannel.read(blockBuffer, location + blockBuffer.position())
                }
            } catch (e: IOException) {
                throw IOException("Could not write to this file channel. Reason: $e", e)
            }
            buffer.position(buffer.position() + bytesToRead)
        }
        dst.position(dst.position() + buffer.position())
        return buffer.position()
    }

    override fun read(dst: ByteBuffer): Int {
        fileDescriptor.operation(write = false) {
            val result = readBuffer(dst, currentPosition.get())
            val newPosition = currentPosition.addAndGet(result.toLong())
            return if (result == 0 && newPosition == size()) -1 else result
        }
    }

    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = fileDescriptor.operation(write = false) {
        dsts.drop(offset).take(length).sumByLong { read(it).toLong() }
    }

    override fun read(dst: ByteBuffer, position: Long): Int = fileDescriptor.operation(write = false) {
        readBuffer(dst, position)
    }

    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long {
        val bytes = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        fileDescriptor.operation(write = true) {
            val buffer = ByteBuffer.allocate(bytes)
            src.read(buffer)
            buffer.flip()
            return write(buffer, position).toLong()
        }
    }

    override fun lock(position: Long, size: Long, shared: Boolean) = throw UnsupportedOperationException()

    override fun tryLock(position: Long, size: Long, shared: Boolean) = throw UnsupportedOperationException()

    override fun map(mode: MapMode?, position: Long, size: Long): MappedByteBuffer = throw UnsupportedOperationException()
}