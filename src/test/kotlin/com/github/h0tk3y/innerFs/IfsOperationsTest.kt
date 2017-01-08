package com.github.h0tk3y.innerFs

import com.github.h0tk3y.innerFs.dsl.div
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.DosFileAttributeView


@ExtendWith(IfsExternalResource::class)
class IfsOperationsTest {

    lateinit var ifs: InnerFileSystem

    @Test fun testHasUnallocatedEntry() {
        val entry = ifs.getFreeBlocks()
        assertNotNull(entry)
    }

    @Test fun locateRoot() {
        val location = ifs.locateBlock(ifs.rootDirectories.single())
        assertEquals(0L, location)

        val entry = ifs.locateEntry(ifs.rootDirectories.single())
        assertNull(entry)
    }

    @Test fun allocateDeallocate() {
        val originalSize = ifs.underlyingChannel.size()
        val allocatedBlock = ifs.allocateBlock { }
        assertEquals(originalSize + BLOCK_SIZE, ifs.underlyingChannel.size())

        ifs.deallocateBlock(allocatedBlock)
        val freeBlock = ifs.getFreeBlocks().entry.firstBlockLocation
        assertEquals(allocatedBlock, freeBlock)

        val allocatedAgain = ifs.allocateBlock { }
        assertEquals(allocatedBlock, allocatedAgain)
    }

    @Test fun freeBlocksChain() {
        val blocks = (1..10).map { ifs.allocateBlock {} }
        val deallocated = blocks.filterIndexed { index, _ -> index % 2 == 0 }
        deallocated.forEach { ifs.deallocateBlock(it) }

        val reallocated = (1..deallocated.size).map { ifs.allocateBlock {} }
        assertEquals(deallocated.toSet(), reallocated.toSet())
    }

    @Test fun defaultDirectoryEntriesRead() {
        val entries = ifs.entriesFromBlocksAt(0L).toList()
        assertTrue(entries[0].value.isFreeBlocksEntry)
        assertTrue(entries.drop(1).all { !it.entry.exists })
        assertTrue(entries.drop(1).all { it.entry.name == EMPTY_ENTRY_NAME })
        assertTrue(entries.drop(1).all { (it.location - BlockHeader.size) % DirectoryEntry.size == 0L })
    }

    @Test fun readOnlyFs() {
        Files.createDirectories(ifs / "a")
        Files.newByteChannel(ifs / "a" / "1.txt", CREATE, WRITE).use { it.write(ByteBuffer.allocate(123)) }

        val path = ifs.underlyingPath
        ifs.close()
        val attrs = Files.getFileAttributeView(path, DosFileAttributeView::class.java)
        if (attrs != null) {
            attrs.setReadOnly(true)

            InnerFileSystemProvider().newFileSystem(path).use { newIfs ->
                Assertions.assertTrue(newIfs.isReadOnly)
                Assertions.assertTrue(newIfs.fileStores.single().isReadOnly)
                Assertions.assertTrue(Files.exists(newIfs / "a" / "1.txt"))
                Assertions.assertEquals(123L, Files.size(newIfs / "a" / "1.txt"))
            }

            attrs.setReadOnly(false)
        }
    }
}