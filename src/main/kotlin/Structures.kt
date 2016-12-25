import java.nio.ByteBuffer

const val MAX_NAME_SIZE = 256
const val BLOCK_SIZE = 4096

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

data class BlockHeader(val nextBlockLocation: Long) : ByteStructure {
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

data class DirectoryEntry(
        val isDirectory: Boolean,
        val firstBlockLocation: Long,
        val name: String
) : ByteStructure {
    val nameBytes get() = name.toByteArray(Charsets.UTF_8)

    init {
        require(nameBytes.size <= MAX_NAME_SIZE) {
            "An entry name should fit into $MAX_NAME_SIZE bytes in UTF-8."
        }
    }

    override fun writeTo(buffer: ByteBuffer) {
        with(buffer) {
            put(isDirectory.asByte())
            putLong(firstBlockLocation)

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
            val nameBytesSize = getInt()
            val name = ByteArray(nameBytesSize).let { get(it); String(it, Charsets.UTF_8) }

            return DirectoryEntry(isDirectory, firstBlockLocation, name)
        }

        val size by lazy { measureSize(DirectoryEntry(false, 0L, "")) }
    }
}