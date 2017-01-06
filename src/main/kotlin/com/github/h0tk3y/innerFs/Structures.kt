package com.github.h0tk3y.innerFs

import java.nio.ByteBuffer

internal const val BLOCK_SIZE = 4096
internal const val FREE_BLOCKS_ENTRY_FLAG_SIZE = -2L

internal interface ByteStructure {
    fun writeTo(buffer: ByteBuffer)
}

internal fun ByteStructure.bytes(): ByteBuffer {
    val buffer = ByteBuffer.allocate(measureSize(this))
    writeTo(buffer)
    buffer.flip()
    return buffer
}

internal fun measureSize(byteStructure: ByteStructure): Int {
    val buffer = ByteBuffer.allocate(BLOCK_SIZE)
    byteStructure.writeTo(buffer)
    return buffer.position()
}

internal data class BlockHeader(val nextBlockLocation: Long) : ByteStructure {
    override fun writeTo(buffer: ByteBuffer) {
        buffer.putLong(nextBlockLocation)
    }

    companion object {
        const val NO_NEXT_BLOCK = 0L // root block cannot be next block, so it's safe to use 0

        fun read(from: ByteBuffer): BlockHeader {
            val nextBlockLocation = from.getLong()
            return BlockHeader(nextBlockLocation)
        }

        val size by lazy { measureSize(BlockHeader(0L)) }
    }
}

internal data class DirectoryEntry(
        val isDirectory: Boolean,
        val firstBlockLocation: Long,
        val size: Long,
        val name: String
) : ByteStructure {
    val nameBytes get() = name.toByteArray(Charsets.UTF_8)

    val exists get() = firstBlockLocation > BlockHeader.NO_NEXT_BLOCK
    internal val isFreeBlocksEntry get() = size == FREE_BLOCKS_ENTRY_FLAG_SIZE

    init {
        require(NameChecker.isValidName(name))
    }

    override fun writeTo(buffer: ByteBuffer) {
        with(buffer) {
            put(isDirectory.asByte())
            putLong(firstBlockLocation)
            putLong(size)
            val nameBytes = nameBytes
            putInt(nameBytes.size)
            put(nameBytes)
            put(ByteArray(MAX_NAME_SIZE - nameBytes.size))
        }
    }

    companion object {
        fun read(from: ByteBuffer): DirectoryEntry = with(from) {
            val isDirectory = get().asBoolean()
            val firstBlockLocation = getLong()
            val size = getLong()
            val nameBytesSize = getInt()
            val name = ByteArray(MAX_NAME_SIZE).let { get(it); String(it, 0, nameBytesSize, Charsets.UTF_8) }

            return DirectoryEntry(isDirectory, firstBlockLocation, size, name)
        }

        val size by lazy { measureSize(DirectoryEntry(false, 0L, 0L, "")) }
    }
}

internal val entriesInBlock = (BLOCK_SIZE - BlockHeader.size) / DirectoryEntry.size

internal val dataBytesInBlock = BLOCK_SIZE - BlockHeader.size