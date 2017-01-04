package com.github.h0tk3y.innerFs

/**
 * Created by igushs on 1/1/17.
 */

internal enum class CriticalLevel { READ, WRITE, WRITE_WITH_PARENT }

internal class FileDescriptor(val innerFs: InnerFileSystem,
                              val parentLocation: Long,
                              locatedEntry: Located<DirectoryEntry>) {
    @Volatile
    var directoryEntry = locatedEntry
        private set

    val fileLocation: Long
        get() = directoryEntry.entry.firstBlockLocation

    internal inline fun <T> critical(criticalLevel: CriticalLevel, action: () -> T) = when (criticalLevel) {
        CriticalLevel.READ -> innerFs.criticalForBlock(fileLocation, false, action)
        CriticalLevel.WRITE -> innerFs.criticalForBlock(fileLocation, true, action)
        CriticalLevel.WRITE_WITH_PARENT ->
            innerFs.criticalForBlock(parentLocation, true) {
                innerFs.criticalForBlock(fileLocation, true, action)
            }
    }


    var size: Long
        get() = directoryEntry.entry.size
        set(value) {
            directoryEntry = directoryEntry.copy(value = directoryEntry.entry.copy(size = value))
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

    fun openOneFile() {
        innerFs.openFileDescriptors.increase(fileLocation)
    }

    fun closeOneFile() {
        synchronized(innerFs.openFileDescriptors) {
            innerFs.openFileDescriptors.decrease(fileLocation)
        }
    }
}