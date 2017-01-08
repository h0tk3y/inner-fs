package com.github.h0tk3y.innerFs

internal enum class CriticalLevel { READ, WRITE, WRITE_WITH_PARENT }

/**
 * In-memory structure for open files of an [innerFs]. A single file should have only one
 * instance of FileDescriptor, and the users of that file exchange the file details update (e.g. its size) through
 * this FileDescriptor.
 * Manages locks for the file.
 */
internal class FileDescriptor(val innerFs: InnerFileSystem,
                              val parentLocation: Long,
                              locatedEntry: Located<DirectoryEntry>) {
    @Volatile
    private var directoryEntry = locatedEntry

    val fileLocation: Long
        get() = directoryEntry.entry.firstBlockLocation

    internal inline fun <T> operation(criticalLevel: CriticalLevel, action: () -> T): T {
        try {
            val result = when (criticalLevel) {
                CriticalLevel.READ -> innerFs.criticalForBlock(fileLocation, false, action)
                CriticalLevel.WRITE -> innerFs.criticalForBlock(fileLocation, true, action)
                CriticalLevel.WRITE_WITH_PARENT ->
                    innerFs.criticalForBlock(parentLocation, true) {
                        innerFs.criticalForBlock(fileLocation, true, action)
                    }
            }
            return result
        } finally {
            when (criticalLevel) {
                CriticalLevel.READ -> updateAccessTime()
                CriticalLevel.WRITE, CriticalLevel.WRITE_WITH_PARENT -> updateModifiedTime()
            }
        }
    }

    private fun updateAccessTime() {
        if (!innerFs.isReadOnly) {
            val now = System.currentTimeMillis()
            directoryEntry = directoryEntry.copy(value = directoryEntry.value.copy(lastAccessTimeMillis = now))
            innerFs.criticalForBlock(parentLocation, write = true) {
                innerFs.rewriteEntry(parentLocation, directoryEntry.location, directoryEntry.entry)
            }
        }
    }

    private fun updateModifiedTime() {
        if (!innerFs.isReadOnly) {
            val now = System.currentTimeMillis()
            directoryEntry = directoryEntry.copy(value = directoryEntry.value.copy(lastModifiedTimeMillis = now))
            innerFs.criticalForBlock(parentLocation, write = true) {
                innerFs.rewriteEntry(parentLocation, directoryEntry.location, directoryEntry.entry)
            }
        }
    }

    val blockLocator = BlockLocator(initialBlockLocation = fileLocation) { BlockHeader.read(innerFs.readBlock(it)) }

    var size: Long
        get() = directoryEntry.entry.size
        set(value) {
            directoryEntry = directoryEntry.copy(value = directoryEntry.entry.copy(size = value))
            innerFs.rewriteEntry(parentLocation,
                                 directoryEntry.location,
                                 directoryEntry.entry)
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileDescriptor) return false

        if (parentLocation != other.parentLocation) return false
        if (fileLocation != other.fileLocation) return false

        return true
    }

    override fun hashCode(): Int {
        var result = parentLocation.hashCode()
        result = 31 * result + parentLocation.hashCode()
        result = 31 * result + fileLocation.hashCode()
        return result
    }

    fun openOne() {
        innerFs.openFileDescriptors.increase(fileLocation)
    }

    fun closeOne() {
        synchronized(innerFs.openFileDescriptors) {
            innerFs.openFileDescriptors.decrease(fileLocation)
        }
    }
}