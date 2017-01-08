package com.github.h0tk3y.innerFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ByteStructuresTest {
    @Test fun blockHeader() {
        val blockHeader = BlockHeader(12345L)
        val bytes = blockHeader.bytes()

        assertEquals(BlockHeader.size, bytes.limit())

        val deserialized = BlockHeader.read(bytes)
        assertEquals(blockHeader, deserialized)
    }

    @Test fun directoryEntry() {
        val entry = DirectoryEntry(true, 12345L, 54321L, 1L, 2L, 3L, "someMeaninglessName")
        val bytes = entry.bytes()

        assertEquals(DirectoryEntry.size, bytes.limit())

        val deserialized = DirectoryEntry.read(bytes)
        assertEquals(entry, deserialized)
    }
}