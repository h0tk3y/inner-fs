package com.github.h0tk3y.innerFs

import java.nio.file.FileStore
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView

internal class InnerFileStore(val innerFs: InnerFileSystem) : FileStore() {
    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>?): V? = null

    override fun getAttribute(attribute: String?): Any = throw UnsupportedOperationException()

    override fun getTotalSpace(): Long = innerFs.underlyingChannel.size()

    override fun getUsableSpace(): Long = innerFs.underlyingChannel.size() * (BLOCK_SIZE - BlockHeader.size) / BLOCK_SIZE

    override fun type(): String = "ifs"

    override fun supportsFileAttributeView(type: Class<out FileAttributeView>) =
            BasicFileAttributeView::class.java.isAssignableFrom(type)

    override fun supportsFileAttributeView(name: String?): Boolean = name == "basic"

    override fun name() = "InnerFS root of $innerFs"

    override fun isReadOnly() = innerFs.isReadOnly

    override fun getUnallocatedSpace() = innerFs.criticalForBlock(UNALLOCATED_BLOCKS, write = false) {
        val firstBlockLocation = innerFs.getFreeBlocks().entry.firstBlockLocation
        if (firstBlockLocation == BlockHeader.NO_NEXT_BLOCK)
            0L else
            innerFs.blocksSequence(firstBlockLocation).count() * BLOCK_SIZE.toLong()
    }
}