package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.*
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.NonWritableChannelException
import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock

internal const val ROOT_LOCATION = 0L
internal const val UNALLOCATED_BLOCKS = -1L

private val singleInnerFileSystemProvider = InnerFileSystemProvider()

class InnerFileSystem internal constructor(val underlyingPath: Path,
                                           createNew: Boolean) : FileSystem() {

    private val writable: Boolean

    private fun checkWritable() {
        if (!writable)
            throw FileSystemException("This file system is not writable")
    }

    internal val underlyingChannel: FileChannel

    private val blockLocksMap = ConcurrentHashMap<Long, ReadWriteLock>()
    internal val lockCounters = EnterExitCounterMap<Long>(
            { blockLocksMap[it] = ReentrantReadWriteLock() },
            { blockLocksMap.remove(it) })

    internal val fileDescriptorByBlock = ConcurrentHashMap<Long, FileDescriptor>()

    // Also used as a monitor for the descriptor-sensitive operations (e.g. checking if a file is open before delete)
    internal val openFileDescriptors = EnterExitCounterMap<Long>({ }, { fileDescriptorByBlock.remove(it) })

    private val singleFileStore = InnerFileStore(this)
    private val underlyingFileLock: FileLock?

    init {
        if (createNew && !Files.exists(underlyingPath)) {
            Files.createFile(underlyingPath)
        }
        writable = Files.isWritable(underlyingPath)
        val modifiers = setOf(StandardOpenOption.READ) +
                        (if (writable) setOf(StandardOpenOption.WRITE) else emptySet())
        try {
            underlyingChannel = FileChannel.open(underlyingPath, modifiers)
            underlyingFileLock = try {
                underlyingChannel.lock()
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: NonWritableChannelException) {
                null
            }
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

    override fun isReadOnly(): Boolean = !writable

    override fun getFileStores(): Iterable<FileStore> = listOf(singleFileStore)

    override fun getPath(first: String, vararg more: String?) =
            InnerPath(this,
                      if (first.isEmpty() && more.isEmpty())
                          emptyList() else
                          first.split("/") + more.toList().filterNotNull())

    // Currently, all the file systems within a class loader return the same provider because all
    // the instances of `InnerFileSystemProvider` are identical and store no state. This should
    // be changed if some difference between the providers is introduced (e.g. they are given
    // some file system parameters, e.g. block size).
    override fun provider(): InnerFileSystemProvider = singleInnerFileSystemProvider

    override fun isOpen(): Boolean = underlyingChannel.isOpen

    override fun getUserPrincipalLookupService(): UserPrincipalLookupService = throw NotImplementedError()

    override fun close() {
        if (underlyingChannel.isOpen) underlyingFileLock?.release()
        underlyingChannel.close()
    }

    override fun getPathMatcher(syntaxAndPattern: String?): PathMatcher =
            throw UnsupportedOperationException("Path matcher is not yet supported for InnerFS")

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
        criticalForBlock(ROOT_LOCATION, write = true) {
            check(underlyingChannel.size() == 0L) { "This is only done in the init phase." }
            val bytes = ByteBuffer.allocateDirect(BLOCK_SIZE)
            BlockHeader(BlockHeader.NO_NEXT_BLOCK).writeTo(bytes)
            initializeDirectoryBlock(bytes)
            bytes.position(0)
            writeOut(bytes, ROOT_LOCATION)
            val (firstEntryLocation, _) = entriesFromBlocksAt(ROOT_LOCATION).first()
            val now = System.currentTimeMillis()
            rewriteEntry(firstEntryLocation, DirectoryEntry(false, BlockHeader.NO_NEXT_BLOCK, FREE_BLOCKS_ENTRY_FLAG_SIZE,
                                                            now, now, now, "unallocated"))
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
        checkWritable()
        val b = buffer.slice()
        while (b.hasRemaining()) {
            underlyingChannel.write(b, toLocation + b.position())
        }
    }

    internal fun locateBlock(path: InnerPath, startingFromLocation: Long = ROOT_LOCATION) =
            locateBlock(path, false, startingFromLocation) { it }

    /**
     * Given a [path], locates its block starting from [startingFromLocation] block, and then, while still holding
     * the innermost directory lock, calculates the [transform] of the found block and returns it.
     * Returns null if no block was found.
     */
    private fun <T> locateBlock(path: InnerPath, write: Boolean = false, startingFromLocation: Long = ROOT_LOCATION, transform: (Long) -> T): T? {
        var currentBlock = startingFromLocation
        for (i in path.pathSegments.indices.drop(if (path.isAbsolute) 1 else 0)) {
            criticalForBlock(currentBlock, write) {
                val located = entriesFromBlocksAt(currentBlock).find { (_, e) ->
                    e.exists &&
                    e.name == path.pathSegments[i] &&
                    e.isDirectory
                } ?: return null
                val nextBlock = located.entry.firstBlockLocation
                if (i == path.pathSegments.lastIndex) {
                    return transform(nextBlock)
                }
                currentBlock = nextBlock
            }
        }
        return transform(currentBlock)
    }

    internal fun locateEntry(path: InnerPath, startingFromLocation: Long = ROOT_LOCATION) =
            locateEntry(path, startingFromLocation, false) { it }

    /**
     * Locates a file system entry for the given [path], starting from the given [startingFromLocation] and
     * then, still holding the lock of the parent directory, calculates the [transform] of the
     * found [Located] [DirectoryEntry]. Returns null if no entry was found.
     */
    internal fun <T> locateEntry(path: InnerPath,
                                 startingFromLocation: Long = ROOT_LOCATION,
                                 write: Boolean = false,
                                 transform: (entry: Located<DirectoryEntry>) -> T): T? {
        if (path == path.root)
            return null

        val parent = path.parent
        return locateBlock(parent ?: InnerPath(this, emptyList()), write, startingFromLocation) { parentBlock ->
            criticalForBlock(parentBlock, write) {
                val entry = entriesFromBlocksAt(parentBlock).find { it.entry.exists && path.fileNameString == it.entry.name }
                if (entry == null) null else
                    transform(entry)
            }
        }
    }

    /**
     * Each pairs in the generated sequence is the location of the block in the underlying file and the buffer with
     * the block data, pointing at the block start (including the header).
     */
    internal fun blocksSequence(firstBlockLocation: Long): Sequence<Located<ByteBuffer>> = Sequence {
        var currentBlock = firstBlockLocation
        generateSequence {
            if (currentBlock == BlockHeader.NO_NEXT_BLOCK)
                return@generateSequence null

            val bytes = readBlock(currentBlock)
            val header = BlockHeader.read(bytes)
            bytes.position(0)
            val result = Located(currentBlock, bytes)
            currentBlock = header.nextBlockLocation
            result
        }.iterator()
    }

    /**
     * Produces a sequence of [Located] [DirectoryEntry] from a sequence of [Located] data blocks represented bytes.
     */
    private fun entriesFromBlocks(blocks: Sequence<Located<ByteBuffer>>): Sequence<Located<DirectoryEntry>> = blocks
            .flatMap { (location, data) ->
                data.position(BlockHeader.size) //Skip the header
                generateSequence {
                    val position = data.position()
                    val entry = DirectoryEntry.read(data)
                    Located(location + position, entry)
                }.take(entriesInBlock)
            }

    internal fun entriesFromBlocksAt(firstBlockLocation: Long) = entriesFromBlocks(blocksSequence(firstBlockLocation))

    /**
     * Rewrites a [DirectoryEntry]  located at [entryLocation] with the given [newEntry].
     * Should be called in a [criticalForBlock] block for the parent directory location.
     */
    internal fun rewriteEntry(
            entryLocation: Long,
            newEntry: DirectoryEntry) {
        val bytes = newEntry.bytes()
        writeOut(bytes, entryLocation + bytes.position())
    }

    internal fun getFreeBlocks(): Located<DirectoryEntry> =
            entriesFromBlocksAt(0)
                    .filter { (_, entry) -> entry.isFreeBlocksEntry } // only the free blocks ptr can be negative
                    .single()

    internal fun deallocateBlock(blockLocation: Long) {
        criticalForBlock(UNALLOCATED_BLOCKS, write = true) {
            val (location, entry) = getFreeBlocks()
            val knownFreeBlock = entry.firstBlockLocation
            val newEntry = entry.copy(firstBlockLocation = blockLocation)
            rewriteEntry(location, newEntry)

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
        checkWritable()
        criticalForBlock(UNALLOCATED_BLOCKS, write = true) {
            val freeBlocks = getFreeBlocks()

            val knownFreeBlock = freeBlocks.entry.firstBlockLocation // to positive

            val result = if (knownFreeBlock != BlockHeader.NO_NEXT_BLOCK)
                knownFreeBlock else
                appendNewBlockToBackingFile()

            val buffer = ByteBuffer.allocateDirect(BLOCK_SIZE).apply { readBlock(result, this) }

            // get the next free block to store it in the root entry instead
            val header = BlockHeader.read(buffer)
            val nextFreeBlock = header.nextBlockLocation
            rewriteEntry(freeBlocks.location, freeBlocks.entry.copy(firstBlockLocation = nextFreeBlock))

            // then initialize the allocated block
            buffer.position(0)
            header.copy(nextBlockLocation = BlockHeader.NO_NEXT_BLOCK).writeTo(buffer)
            initData(buffer.slice())
            buffer.position(0)
            writeOut(buffer, result)

            return result
        }
    }

    // Should be called with lock on the [directoryFirstBlockLocation].
    private fun addEntryToDirectory(directoryFirstBlockLocation: Long, newEntry: DirectoryEntry): Located<DirectoryEntry> {
        checkWritable()
        val blocksSequence = blocksSequence(directoryFirstBlockLocation)
        val existingEmptySlot = entriesFromBlocks(blocksSequence).find { !it.entry.exists && !it.entry.isFreeBlocksEntry }
        if (existingEmptySlot != null) {
            rewriteEntry(existingEmptySlot.location, newEntry)
            return Located(existingEmptySlot.location, newEntry)
        } else {
            val allocatedBlock = allocateBlock(initializeDirectoryBlock)
            val (lastBlockLocation, _) = blocksSequence.last()
            setBlockAsNext(lastBlockLocation, allocatedBlock)
            return addEntryToDirectory(directoryFirstBlockLocation, newEntry)
        }
    }

    //Should be called with a write lock taken on the target file
    internal fun setBlockAsNext(lastBlockLocation: Long, nextBlockLocation: Long) {
        checkWritable()
        val newHeader = BlockHeader(nextBlockLocation = nextBlockLocation)
        writeOut(newHeader.bytes(), lastBlockLocation)
    }

    enum class CreateMode { OPEN_OR_FAIL, CREATE_OR_OPEN, CREATE_OR_FAIL }

    fun directorySequence(path: InnerPath): Sequence<Path> {
        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        val location = if (path.root == path)
            ROOT_LOCATION else
            locateEntry(path, 0L)?.entry
                    ?.apply { if (!isDirectory) throw NotDirectoryException("File '$path' is not a directory") }
                    ?.firstBlockLocation
            ?: throw NoSuchFileException("$path")

        return entriesFromBlocksAt(location)
                .filter { it.entry.exists }
                .map { InnerPath(this, path.pathSegments + it.entry.name) }
    }

    // Should be only called in critical sections for the directory location.
    private fun markEntryDeleted(entryLocation: Long) {
        rewriteEntry(entryLocation, DirectoryEntry(false, BlockHeader.NO_NEXT_BLOCK, -1,
                                                   0L, 0L, 0L, EMPTY_ENTRY_NAME))
    }

    fun delete(path: InnerPath) {
        checkWritable()
        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        require(path != path.root) { "Root file '/' cannot be deleted" }
        locateEntry(path, write = true) { (location, entry) ->
            criticalForBlock(entry.firstBlockLocation, false) {
                synchronized(openFileDescriptors) {

                    if (entry.isDirectory) {
                        val hasEntries = entriesFromBlocksAt(entry.firstBlockLocation).any { (_, v) -> v.exists }
                        if (hasEntries)
                            throw DirectoryNotEmptyException("$path")
                    }

                    if (fileDescriptorByBlock.containsKey(entry.firstBlockLocation))
                        throw FileIsInUseException(path, "Cannot delete the file")
                    blocksSequence(entry.firstBlockLocation).forEach { (location, _) -> deallocateBlock(location) }
                    markEntryDeleted(location)
                }
            }
        } ?: throw NoSuchFileException("'$path'")
    }

    fun openFile(path: InnerPath,
                 read: Boolean = true,
                 write: Boolean = true,
                 append: Boolean = false,
                 truncateExisting: Boolean = false,
                 create: CreateMode = CREATE_OR_OPEN): FileChannel {
        if (write || truncateExisting)
            checkWritable()

        require(path.innerFs == this) { "The path '$path' doesn't belong to this file system" }
        require(path.isAbsolute) { "The path should be absolute." }
        val parent = path.normalize().parent!!
        val parentLocation = locateBlock(parent, ROOT_LOCATION)
                             ?: throw NoSuchFileException("$parent")

        criticalForBlock(parentLocation, write = true) {
            val locatedEntry = entriesFromBlocksAt(parentLocation).find { (_, e) -> e.name == path.fileNameString }

            if (locatedEntry != null && !locatedEntry.entry.isDirectory) {
                val fileLocation = locatedEntry.entry.firstBlockLocation
                synchronized(openFileDescriptors) {
                    var actualEntry: Located<DirectoryEntry> = locatedEntry // may be rewritten by truncating
                    if (truncateExisting) {
                        if (!fileDescriptorByBlock.containsKey(fileLocation)) {
                            blocksSequence(fileLocation).forEach { deallocateBlock(it.location) }
                            val block = allocateBlock { initializeDataBlock }
                            actualEntry =
                                    actualEntry.copy(value = actualEntry.entry.copy(firstBlockLocation = block, size = 0))
                            rewriteEntry(actualEntry.location, actualEntry.entry)
                        } else throw FileIsInUseException(path, "Cannot truncate the file")
                    }
                    val fd = fileDescriptorByBlock.getOrPut(fileLocation) {
                        FileDescriptor(this, parentLocation, actualEntry)
                    }
                    fd.openOne()
                    if (create == CREATE_OR_FAIL)
                        throw FileAlreadyExistsException("File '$path' already exists.")
                    return BlocksFileChannel(fd, read, write, append)
                }
            }

            if (create == OPEN_OR_FAIL)
                throw NoSuchFileException("File doesn't exist for path '$path'")

            if (locatedEntry?.entry?.isDirectory ?: false)
                throw NoSuchFileException("The path '$path' points to a directory")

            val dataBlock = allocateBlock(initializeDataBlock)
            synchronized(openFileDescriptors) {
                val now = System.currentTimeMillis()
                val directoryEntry = DirectoryEntry(false, dataBlock, ROOT_LOCATION,
                                                    now, now, now, path.pathSegments.last())
                val e = addEntryToDirectory(parentLocation, directoryEntry)
                val fd = FileDescriptor(this, parentLocation, e)
                fd.openOne()
                fileDescriptorByBlock[dataBlock] = fd
                return BlocksFileChannel(fd, read, write, append)
            }
        }
    }

    fun move(from: InnerPath, to: InnerPath, replaceExisting: Boolean = false) {
        require(from.isAbsolute)
        require(to.isAbsolute)

        if (from.normalize() == to.normalize())
            return

        directoriesOperation(from, to) { fromParent, toParent ->
            if (locateEntry(from.fileName, fromParent)?.entry?.isDirectory ?: false)
                throw IOException("Directory $from cannot be moved. Use `Files.walkFileTree` + `copy` instead")

            if (to.innerFs == this) {
                val (sLocation, sEntry) = locateEntry(from.fileName, fromParent) ?: throw NoSuchFileException("$from")
                val resultEntry = sEntry.copy(name = to.fileNameString)
                val locatedPEntry = locateEntry(to, toParent)
                if (locatedPEntry != null) {
                    if (replaceExisting)
                        throw FileAlreadyExistsException("$to")
                    delete(to)
                    rewriteEntry(locatedPEntry.location, resultEntry)
                } else {
                    addEntryToDirectory(toParent, resultEntry)
                }
                criticalForBlock(sEntry.firstBlockLocation, write = true) {
                    synchronized(openFileDescriptors) {
                        if (from.innerFs.fileDescriptorByBlock.containsKey(sEntry.firstBlockLocation))
                            throw FileIsInUseException(from, "Cannot move the file")
                        markEntryDeleted(sLocation)
                    }
                }
            } else {
                openFile(from, read = true, create = OPEN_OR_FAIL).use { sFile ->
                    if (Files.exists(to))
                        if (replaceExisting)
                            throw FileAlreadyExistsException("$to") else
                            Files.delete(to)
                    to.innerFs.openFile(to, write = true, create = CREATE_OR_FAIL).use { pFile ->
                        channelCopy(sFile, pFile)
                    }
                }
            }
        }
    }

    fun copy(from: InnerPath, to: InnerPath, replaceExisting: Boolean) {
        require(from.isAbsolute)
        require(to.isAbsolute)

        if (Files.isSameFile(from, to))
            return

        if (Files.isDirectory(from)) {
            Files.createDirectory(to)
        } else {
            openFile(from, read = true, create = OPEN_OR_FAIL).use { sFile ->
                if (Files.exists(to))
                    if (replaceExisting)
                        throw FileAlreadyExistsException("$to") else
                        Files.delete(to)
                to.innerFs.openFile(to, write = true, create = CREATE_OR_OPEN).use { pFile ->
                    channelCopy(sFile, pFile)
                }
            }
        }
    }

    /**
     * Executes [actions], acquires locks for parents of [s] and [p] in a correct order.
     */
    private inline fun directoriesOperation(s: InnerPath, p: InnerPath,
                                            actions: (fromLocation: Long, toLocation: Long) -> Unit) {
        val sParent = requireInnerFsPath(s.parent?.normalize())
        val pParent = requireInnerFsPath(p.parent?.normalize())
        val sParentBlock = s.innerFs.locateBlock(sParent) ?: throw NoSuchFileException("$sParent")
        val pParentBlock = p.innerFs.locateBlock(pParent) ?: throw NoSuchFileException("$pParent")
        // To maintain the globally ordered locking, check if one of the paths is an ancestor of the other
        // and if not, lock the block which has lower number first
        val outer = when {
            s < p -> sParentBlock
            else -> pParentBlock
        }
        val outerFs = if (outer == sParentBlock) s.innerFs else p.innerFs
        val inner = if (outer == sParentBlock) pParentBlock else sParentBlock
        val innerFs = if (outerFs == s.innerFs) p.innerFs else s.innerFs
        outerFs.criticalForBlock(outer, write = true) {
            innerFs.criticalForBlock(inner, write = true) {
                actions(sParentBlock, pParentBlock)
            }
        }
    }

    fun createDirectory(path: InnerPath, failIfExists: Boolean = true, createMissingDirectories: Boolean = false) {
        if (createMissingDirectories) {
            Files.createDirectories(path)
            return
        }

        if (failIfExists)
            checkWritable()

        require(path.isAbsolute)
        require(path != path.root)

        val parent = path.parent!!
        val parentBlock = locateBlock(parent) ?: throw NoSuchFileException("$parent")
        criticalForBlock(parentBlock, write = true) {
            val entries = entriesFromBlocksAt(parentBlock)
            if (entries.any { (_, e) -> e.name == path.fileNameString })
                if (failIfExists)
                    throw FileAlreadyExistsException("$path") else
                    return
            val dataBlock = allocateBlock(initializeDirectoryBlock)
            val now = System.currentTimeMillis()
            addEntryToDirectory(parentBlock, DirectoryEntry(true, dataBlock, 0L, now, now, now, path.fileNameString))
        }
    }
}
