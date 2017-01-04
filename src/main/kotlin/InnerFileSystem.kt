import InnerFileSystem.CreateMode.*
import kotlinx.coroutines.generate
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.spi.FileSystemProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal const val ROOT_LOCATION = 0L

class InnerFileSystem(val underlyingPath: Path,
                      val provider: InnerFileSystemProvider,
                      createNew: Boolean) : FileSystem() {

    private val writable: Boolean

    internal val underlyingChannel: FileChannel

    private val blockLocksMap = ConcurrentHashMap<Long, ReadWriteLock>()
    internal val lockCounters = EnterExitCounterMap<Long>(
            { blockLocksMap[it] = ReentrantReadWriteLock() },
            { blockLocksMap.remove(it) })

    internal val fileDescriptorByBlock = ConcurrentHashMap<Long, FileDescriptor>()
    internal val openFileDescriptors = EnterExitCounterMap<Long>({}, { fileDescriptorByBlock.remove(it) })

    private val singleFileStore = InnerFileStore(this)
    private val underlyingFileLock: FileLock

    init {
        if (createNew && !Files.exists(underlyingPath)) {
            Files.createFile(underlyingPath)
        }
        writable = Files.isWritable(underlyingPath)
        val modifiers = setOf(StandardOpenOption.READ) +
                        (if (writable) setOf(StandardOpenOption.WRITE) else emptySet())
        try {
            underlyingChannel = FileChannel.open(underlyingPath, modifiers)
            underlyingFileLock = underlyingChannel.lock()
            if (createNew)
                writeRootDirectory()
        } catch (e: IOException) {
            throw IOException("Could not initialize an InnerFS. Reason: $e", e)
        }
    }

    //region Java NIO SPI

    override fun getSeparator(): String = "/"

    override fun newWatchService(): WatchService = throw UnsupportedOperationException()

    override fun supportedFileAttributeViews(): Set<String> = setOf("basic")

    override fun isReadOnly(): Boolean = false

    override fun getFileStores(): Iterable<FileStore> = listOf(singleFileStore)

    override fun getPath(first: String, vararg more: String?) =
            InnerPath(this, first.split("/") + more.toList().filterNotNull())

    override fun provider(): FileSystemProvider = provider

    override fun isOpen(): Boolean = underlyingChannel.isOpen

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw NotImplementedError()

    override fun close() {
        if (underlyingChannel.isOpen) underlyingFileLock.release()
        underlyingChannel.close()
    }

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher = TODO("not implemented")

    override fun getRootDirectories(): Iterable<InnerPath> = listOf(InnerPath(this, listOf("")))

    //endregion Java NIO SPI

    internal inline fun <T> criticalForBlock(blockLocation: Long,
                                             write: Boolean,
                                             action: () -> T): T {
        lockCounters.increase(blockLocation)
        val rwLock = blockLocksMap[blockLocation]!!
        val lock = if (write)
            rwLock.writeLock() else
            rwLock.readLock()
        return try {
            lock.withLock(action)
        } finally {
            lockCounters.decrease(blockLocation)
        }
    }

    private fun writeRootDirectory() {
        criticalForBlock(0, write = true) {
            check(underlyingChannel.size() == 0L) { "This is only done in the init phase." }
            val bytes = ByteBuffer.allocateDirect(BLOCK_SIZE)
            BlockHeader(BlockHeader.NO_NEXT_BLOCK).writeTo(bytes)
            initializeDirectoryBlock(bytes)
            bytes.position(0)
            writeOut(bytes, ROOT_LOCATION)
            val (firstEntryLocation, _) = entriesFromBlocksAt(0).first()
            rewriteEntry(0, firstEntryLocation, DirectoryEntry(false, -1, -1, "unallocated"))
        }
    }

    internal fun readBlock(fromLocation: Long): ByteBuffer = ByteBuffer.allocate(BLOCK_SIZE).apply { readBlock(fromLocation, this) }

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
        while (b.hasRemaining()) {
            underlyingChannel.write(b, toLocation + b.position())
        }
    }

    internal fun locateBlock(path: InnerPath, startingFromLocation: Long = ROOT_LOCATION) =
            locate(path, startingFromLocation) { it }

    private fun <T> locate(path: InnerPath, startingFromLocation: Long = ROOT_LOCATION, transform: (Long) -> T): T? {
        fun locateSegmentBlock(location: Long, segmentIndex: Int): T? {
            criticalForBlock(location, write = false) {
                val located = entriesFromBlocksAt(location).find { (_, e) ->
                    e.firstBlockLocation > BlockHeader.NO_NEXT_BLOCK
                    e.name == path.pathSegments[segmentIndex] &&
                    e.isDirectory
                } ?: return null
                val result = located.entry.firstBlockLocation
                if (segmentIndex == path.pathSegments.lastIndex) {
                    return transform(result)
                }
                return locateSegmentBlock(result, segmentIndex + 1)
            }
        }
        if (path == path.root)
            return criticalForBlock(ROOT_LOCATION, write = false) { transform(ROOT_LOCATION) }
        return locateSegmentBlock(startingFromLocation, if (path.isAbsolute) 1 else 0)
    }

    internal fun locateEntry(path: InnerPath, startingFromLocation: Long = ROOT_LOCATION): Located<DirectoryEntry>? {
        require(path.isAbsolute)

        if (path == path.root)
            return null

        val parent = path.parent
        return locate(parent ?: InnerPath(this, emptyList()), startingFromLocation) { parentBlock ->
            criticalForBlock(parentBlock, write = false) {
                entriesFromBlocksAt(parentBlock).find { it.entry.exists && path.fileNameString == it.entry.name }
            }
        }
    }

    /**
     * Each pairs in the generated sequence is the location of the block in the underlying file and the buffer with
     * the block data, pointing at the block start (including the header).
     */
    internal fun blocksSequence(firstBlockLocation: Long): Sequence<Located<ByteBuffer>> = generate {
        var currentBlock = firstBlockLocation
        do {
            val bytes = readBlock(currentBlock)
            val header = BlockHeader.read(bytes)
            bytes.position(0)
            yield(Located(currentBlock, bytes))
            currentBlock = header.nextBlockLocation
        } while (currentBlock != BlockHeader.NO_NEXT_BLOCK)
    }

    /**
     * Produces a sequence of [Located] [DirectoryEntry] from a sequence of [Located] data blocks represented bytes.
     */
    internal fun entriesFromBlocks(blocks: Sequence<Located<ByteBuffer>>): Sequence<Located<DirectoryEntry>> = blocks
            .flatMap { (location, data) ->
                data.position(BlockHeader.size) //Skip the header
                generateSequence {
                    val position = data.position()
                    val entry = DirectoryEntry.read(data)
                    Located(location + position, entry)
                }.take(entriesInBlock)
            }

    internal fun entriesFromBlocksAt(firstBlockLocation: Long) = entriesFromBlocks(blocksSequence(firstBlockLocation))

    internal fun singleEntryFromLocation(location: Long): Located<DirectoryEntry> {
        val bytes = ByteBuffer.allocateDirect(DirectoryEntry.size)
        while (bytes.hasRemaining())
            underlyingChannel.read(bytes)
        return Located(location, DirectoryEntry.read(bytes))
    }

    internal fun rewriteEntry(
            directoryLocation: Long,
            entryLocation: Long,
            newEntry: DirectoryEntry) {
        criticalForBlock(directoryLocation, write = true) {
            val bytes = newEntry.bytes()
            writeOut(bytes, entryLocation + bytes.position())
        }
    }

    internal fun markEntryDeleted(directoryLocation: Long, entryLocation: Long) {
        rewriteEntry(directoryLocation, entryLocation, DirectoryEntry(false, BlockHeader.NO_NEXT_BLOCK, -1, ""))
    }

    internal fun getFreeBlocks(): Located<DirectoryEntry> =
            entriesFromBlocksAt(0)
                    .filter { (_, entry) -> entry.firstBlockLocation < 0 } // only the free blocks ptr can be negative
                    .single()

    internal fun deallocateBlock(blockLocation: Long) {
        criticalForBlock(0, write = true) {
            val (location, entry) = getFreeBlocks()
            val knownFreeBlock = negativeTransform(entry.firstBlockLocation) // to non-negative
            val newEntry = entry.copy(firstBlockLocation = negativeTransform(blockLocation)) // back to negative
            rewriteEntry(ROOT_LOCATION, location, newEntry)

            writeOut(BlockHeader(nextBlockLocation = knownFreeBlock).bytes(), blockLocation)
        }
    }

    private fun appendNewBlockToBackingFile(): Long {
        val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE)
        BlockHeader(BlockHeader.NO_NEXT_BLOCK).writeTo(buffer)
        buffer.position(0)
        val location = underlyingChannel.size()
        writeOut(buffer, location)
        underlyingChannel.force(false)
        return location
    }

    /** Allocates a free block, initializes its data part with [initData] function and returns its start location.*/
    internal fun allocateBlock(initData: (dataBuffer: ByteBuffer) -> Unit): Long {
        criticalForBlock(0, write = true) {
            val freeBlocks = getFreeBlocks()

            val knownFreeBlock = negativeTransform(freeBlocks.entry.firstBlockLocation) // to positive

            val result = if (knownFreeBlock != BlockHeader.NO_NEXT_BLOCK)
                knownFreeBlock else
                appendNewBlockToBackingFile()

            val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE).apply { readBlock(result, this) }

            // get the next free block to store it in the root entry instead
            val header = BlockHeader.read(buffer)
            val nextFreeBlock = header.nextBlockLocation
            rewriteEntry(ROOT_LOCATION, freeBlocks.location,
                         freeBlocks.entry.copy(firstBlockLocation = negativeTransform(nextFreeBlock)))

            // then initialize the allocated block
            buffer.position(0)
            header.copy(nextBlockLocation = BlockHeader.NO_NEXT_BLOCK).writeTo(buffer)
            initData(buffer.slice())
            buffer.position(0)
            writeOut(buffer, result)

            return result
        }
    }

    internal fun addEntryToDirectory(directoryFirstBlockLocation: Long, newEntry: DirectoryEntry): Located<DirectoryEntry> {
        criticalForBlock(directoryFirstBlockLocation, write = true) {
            val blocksSequence = blocksSequence(directoryFirstBlockLocation)
            val existingEmptySlot = entriesFromBlocks(blocksSequence).find { !it.entry.exists && !it.entry.isFreeBlocksEntry }
            if (existingEmptySlot != null) {
                rewriteEntry(directoryFirstBlockLocation, existingEmptySlot.location, newEntry)
                return Located(existingEmptySlot.location, newEntry)
            } else {
                val allocatedBlock = allocateBlock(initializeDirectoryBlock)
                val (lastBlockLocation, _) = blocksSequence.last()
                setBlockAsNext(lastBlockLocation, allocatedBlock)
                return addEntryToDirectory(directoryFirstBlockLocation, newEntry)
            }
        }
    }

    //Should be called with a write lock taken on the target file
    internal fun setBlockAsNext(lastBlockLocation: Long, nextBlockLocation: Long) {
        val newHeader = BlockHeader(nextBlockLocation = nextBlockLocation)
        underlyingChannel.write(newHeader.bytes(), lastBlockLocation)
    }

    enum class CreateMode { FAIL_IF_NOT_EXISTS, CREATE_OR_OPEN, CREATE_OR_FAIL }

    fun directorySequence(path: InnerPath): Sequence<Path> {
        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        val (_, entry) = locateEntry(path, 0L)
                         ?: throw FileNotFoundException("Directory '$path' does not exist and cannot be deleted")
        if (!entry.isDirectory)
            throw NotDirectoryException("File '$path' is not a directory")
        return entriesFromBlocksAt(entry.firstBlockLocation)
                .filter { it.entry.exists }
                .map { InnerPath(this, path.pathSegments + it.entry.name) }
    }

    fun deleteFile(path: InnerPath) {
        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        require(path != path.root) { "Root file '/' cannot be deleted" }
        val parent = path.parent!!
        val parentLocation =
                locateBlock(parent)
                ?: throw NoSuchFileException("$parent", null, "Parent directory '$parent' not found for path '$path'")

        criticalForBlock(parentLocation, write = true) {
            synchronized(openFileDescriptors) {
                val (location, entry) = entriesFromBlocksAt(parentLocation)
                                                .find { (_, e) -> e.name == path.fileNameString }
                                        ?: throw NoSuchFileException("'$path'")
                if (entry.isDirectory && criticalForBlock(entry.firstBlockLocation, false) {
                    entriesFromBlocksAt(entry.firstBlockLocation).filter { it.entry.exists }.any()
                }) {
                    throw DirectoryNotEmptyException("$path")
                }
                val fd = fileDescriptorByBlock[entry.firstBlockLocation]
                if (fd != null)
                    throw FileSystemException("$path", null, "The file cannot be deleted because it is in use")
                blocksSequence(entry.firstBlockLocation).forEach { (location, _) -> deallocateBlock(location) }
                markEntryDeleted(parentLocation, location)
            }
        }
    }

    fun openFile(path: InnerPath,
                 read: Boolean = true,
                 write: Boolean = true,
                 append: Boolean = false,
                 create: CreateMode = CREATE_OR_OPEN): FileChannel {
        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        val parent = path.parent!!
        val parentLocation = locateBlock(parent, ROOT_LOCATION)
                             ?: throw FileNotFoundException("Parent directory '$parent' not found for path '$path'")

        criticalForBlock(parentLocation, write = true) {
            val locatedEntry = entriesFromBlocksAt(parentLocation).find { (_, e) -> e.name == path.pathSegments.last() }

            if (locatedEntry != null && !locatedEntry.entry.isDirectory) {
                val fileLocation = locatedEntry.entry.firstBlockLocation
                synchronized(openFileDescriptors) {
                    val fd = fileDescriptorByBlock.getOrPut(fileLocation) {
                        FileDescriptor(this, parentLocation, locatedEntry)
                    }
                    fd.openOneFile()
                    if (create == CREATE_OR_FAIL)
                        throw FileAlreadyExistsException("File '$path' already exists.")
                    return BlocksFileChannel(fd, read, write, append)
                }
            }

            if (create == FAIL_IF_NOT_EXISTS)
                throw NoSuchFileException("File doesn't exist for path '$path'")

            if (locatedEntry?.entry?.isDirectory ?: false)
                throw NoSuchFileException("The path '$path' points to a directory")

            val dataBlock = allocateBlock(initializeDataBlock)
            synchronized(openFileDescriptors) {
                val directoryEntry = DirectoryEntry(false, dataBlock, ROOT_LOCATION, path.pathSegments.last())
                val e = addEntryToDirectory(parentLocation, directoryEntry)
                val fd = FileDescriptor(this, parentLocation, e)
                fd.openOneFile()
                fileDescriptorByBlock[dataBlock] = fd
                return BlocksFileChannel(fd, read, write, append)
            }
        }
    }

    fun createDirectory(path: InnerPath) {
        require(path.isAbsolute)
        require(path != path.root)

        val parent = path.parent!!
        val parentBlock = locateBlock(parent) ?: throw java.nio.file.NoSuchFileException("$parent")
        criticalForBlock(parentBlock, write = true) {
            val entries = entriesFromBlocksAt(parentBlock)
            if (entries.any { (_, e) -> e.name == path.fileNameString })
                throw FileAlreadyExistsException("$path")
            val dataBlock = allocateBlock(initializeDirectoryBlock)
            addEntryToDirectory(parentBlock, DirectoryEntry(true, dataBlock, 0L, path.fileNameString))
        }
    }
}
