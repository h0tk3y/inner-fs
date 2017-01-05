package com.github.h0tk3y.innerFs

import java.nio.file.FileStore
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView

class InnerFileStore(val innerFs: InnerFileSystem) : FileStore() {
    override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>?): V? = null

    override fun getAttribute(attribute: String?): Any = throw UnsupportedOperationException()

    override fun getTotalSpace(): Long = innerFs.underlyingChannel.size()

    override fun getUsableSpace(): Long = totalSpace

    override fun type(): String = "ifs"

    override fun supportsFileAttributeView(type: Class<out FileAttributeView>) =
            BasicFileAttributeView::class.java.isAssignableFrom(type)

    override fun supportsFileAttributeView(name: String?): Boolean = name == "basic"

    override fun name() = "InnerFS root of $innerFs"

    override fun isReadOnly() = innerFs.isReadOnly

    override fun getUnallocatedSpace() = innerFs.criticalForBlock(UNALLOCATED_BLOCKS, write = false) {
        innerFs.blocksSequence(innerFs.getFreeBlocks().location).count() * BLOCK_SIZE.toLong()
    }
}