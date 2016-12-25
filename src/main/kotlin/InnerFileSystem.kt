import kotlinx.coroutines.generate
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

class InnerFileSystem(val underlyingPath: Path,
                      val provider: InnerFileSystemProvider,
                      createNew: Boolean) : FileSystem() {

    private val writable: Boolean

    internal val underlyingChannel: FileChannel

    //todo optimize locks stored in this map
    private val blockLocksMap = ConcurrentHashMap<BlockToken, ReadWriteLock>()

    private val entriesInBlock = (BLOCK_SIZE - BlockHeader.size) / DirectoryEntry.size

    private val dataBytesInBlock = BLOCK_SIZE - BlockHeader.size

    private val singleFileStore = InnerFileStore(this)

    init {
        if (createNew && !Files.exists(underlyingPath)) {
            Files.createFile(underlyingPath)
        }
        writable = Files.isWritable(underlyingPath)
        val modifiers = setOf(StandardOpenOption.READ) +
                        (if (writable) setOf(StandardOpenOption.WRITE) else emptySet())
        try {
            underlyingChannel = FileChannel.open(underlyingPath, modifiers)
            if (createNew)
                writeRootDirectory()
        } catch (e: IOException) {
            throw IOException("Could not initialize an InnerFS reason: $e", e)
        }
    }

    //region Java NIO SPI

    override fun getSeparator(): String = "/"

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")

    override fun isReadOnly(): Boolean = false

    override fun getFileStores(): Iterable<FileStore> = listOf(singleFileStore)

    override fun getPath(first: String?, vararg more: String?) =
            InnerPath(this, listOf(first!!) + more.toList().filterNotNull())

    override fun provider(): FileSystemProvider = provider

    override fun isOpen(): Boolean = underlyingChannel.isOpen

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw NotImplementedError()

    override fun close() {
        underlyingChannel.close()
    }

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher = TODO("not implemented")

    override fun getRootDirectories(): Iterable<InnerPath> = listOf(InnerPath(this, listOf("")))

    //endregion Java NIO SPI

    private data class BlockToken(val blockLocation: Long)


    private inline fun criticalForBlock(blockLocation: Long,
                                        write: Boolean,
                                        action: () -> Unit) {
        val blockToken = BlockToken(blockLocation)
        val rwLock = blockLocksMap.getOrPut(blockToken) { ReentrantReadWriteLock() }
        val lock = if (write)
            rwLock.writeLock() else
            rwLock.readLock()
        lock.withLock(action)
    }

    private fun writeRootDirectory() {
        criticalForBlock(0, write = true) {
            check(underlyingChannel.size() == 0L) { "This is only done in the init phase." }
            val bytes = ByteBuffer.allocateDirect(BLOCK_SIZE)
            BlockHeader(BlockHeader.NO_NEXT_BLOCK).writeTo(bytes)
            initializeDirectoryBlock(bytes)
            bytes.position(0)
            writeOut(bytes, 0L)
            val (firstEntryLocation, _) = readEntriesFromBlock(0).first()
            rewriteEntry(0, firstEntryLocation, DirectoryEntry(false, -1, "unallocated"))
        }
    }

    private fun readBlock(fromLocation: Long, buffer: ByteBuffer) {
        while (buffer.position() < BLOCK_SIZE) {
            val bytesRead = underlyingChannel.read(buffer, fromLocation + buffer.position())
            if (bytesRead == -1)
                throw IOException("Unexpected EOF while reading the block at location $fromLocation")
        }
        buffer.flip()
        if (buffer.limit() != BLOCK_SIZE)
            throw IOException("Could not read a block from location $fromLocation")
    }

    private fun writeOut(buffer: ByteBuffer, toLocation: Long) {
        val b = buffer.slice()
        while (b.position() < b.limit()) {
            underlyingChannel.write(b, toLocation + b.position())
        }
    }

    internal fun locateBlock(path: InnerPath, startingFromLocation: Long = 0L): Long? {
        var currentBlock = startingFromLocation
        for (segment in path.relativize(rootDirectories.single()).pathSegments) {
            val located = readEntriesFromBlock(currentBlock).find { (_, e) -> e.name == segment && e.isDirectory } ?: return null
            currentBlock = located.entry.firstBlockLocation
        }
        return currentBlock
    }

    internal fun locateEntry(path: InnerPath, startingFromLocation: Long = 0L): LocatedEntry? {
        if (path == path.root)
            return null

        val parentBlock = locateBlock(path.parent ?: rootDirectories.single(), startingFromLocation) ?: return null
        return readEntriesFromBlock(parentBlock).find { InnerPath(this, listOf(it.entry.name)) == path.fileName }
    }

    /**
     * Reads directory entries from directory with its first block starting at
     * specified [location], satisfying the given predicate
     */
    internal fun readEntriesFromBlock(
            location: Long) = generate {
        var nextBlock = location
        val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
        do {
            buffer.position(0)
            buffer.limit(BLOCK_SIZE)
            readBlock(nextBlock, buffer)
            val header = BlockHeader.read(buffer)
            val currentBlockLocation = location
            nextBlock = header.nextBlockLocation
            repeat(entriesInBlock) {
                val position = buffer.position()
                val entry = DirectoryEntry.read(buffer)
                yield(LocatedEntry(currentBlockLocation + position, entry))
            }
        } while (nextBlock != BlockHeader.NO_NEXT_BLOCK)
    }

    internal fun rewriteEntry(
            directoryFirstBlock: Long,
            entryLocation: Long,
            newEntry: DirectoryEntry) {
        criticalForBlock(directoryFirstBlock, write = true) {
            val bytes = newEntry.bytes()
            writeOut(bytes, entryLocation + bytes.position())
        }
    }

    internal fun getFreeBlocks(): LocatedEntry =
            readEntriesFromBlock(0)
                    .filter { (_, entry) -> entry.firstBlockLocation < 0 } // only the free blocks ptr can be negative
                    .single()

    internal fun deallocateBlock(blockLocation: Long) {
        criticalForBlock(0, write = true) {
            val (location, entry) = getFreeBlocks()
            val knownFreeBlock = negativeTransform(entry.firstBlockLocation) // to non-negative
            val newEntry = entry.copy(firstBlockLocation = negativeTransform(blockLocation)) // back to negative
            rewriteEntry(0L, location, newEntry)

            writeOut(BlockHeader(nextBlockLocation = knownFreeBlock).bytes(), blockLocation)
        }
    }

    private fun appendFreeBlock(): Long {
        val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
        BlockHeader(BlockHeader.NO_NEXT_BLOCK).writeTo(buffer)
        buffer.position(0)
        val location = underlyingChannel.size()
        writeOut(buffer, location)
        underlyingChannel.force(false)
        return location
    }

    /** Allocates a free block and returns its start location */
    internal fun allocateBlock(initData: (dataBuffer: ByteBuffer) -> Unit): Long {
        criticalForBlock(0, write = true) {
            val freeBlocks = getFreeBlocks()

            val knownFreeBlock = negativeTransform(freeBlocks.entry.firstBlockLocation) // to positive

            val result = if (knownFreeBlock != BlockHeader.NO_NEXT_BLOCK)
                knownFreeBlock else
                appendFreeBlock()

            val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE).apply { readBlock(result, this) }

            // get the next free block to store it in the root entry instead
            val header = BlockHeader.read(buffer)
            val nextFreeBlock = header.nextBlockLocation
            rewriteEntry(0, freeBlocks.location,
                         freeBlocks.entry.copy(firstBlockLocation = negativeTransform(nextFreeBlock)))

            // then initialize the allocated block
            buffer.position(0)
            header.copy(nextBlockLocation = BlockHeader.NO_NEXT_BLOCK).writeTo(buffer)
            initData(buffer.slice())
            buffer.position(0)
            writeOut(buffer, result)

            return result
        }
        return BlockHeader.NO_NEXT_BLOCK
    }

    private fun initializeDirectoryBlock(dataBuffer: ByteBuffer) {
        val emptyEntry = DirectoryEntry(false, BlockHeader.NO_NEXT_BLOCK, "---")
        for (i in 1..entriesInBlock)
            emptyEntry.writeTo(dataBuffer)
    }

    private fun initializeDataBlock(dataBuffer: ByteBuffer) {
        dataBuffer.put(ByteBuffer.allocateDirect(dataBytesInBlock))
    }
}
