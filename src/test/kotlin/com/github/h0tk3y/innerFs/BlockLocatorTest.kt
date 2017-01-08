package com.github.h0tk3y.innerFs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BlockLocatorTest {
    @Test fun empty() {
        val map = BlockLocator(BlockHeader.NO_NEXT_BLOCK) { throw NotImplementedError() }
        val l = map.locate(map.blockDataSize.toLong() / 2)

        assertNull(l)
    }

    @Test fun negativeLocation() {
        val offsets = mapOf(0L to 1000L,
                            1000L to 2000L,
                            2000L to 3000L)

        val locator = BlockLocator(0L, 1000) {
            BlockHeader(offsets[it] ?: BlockHeader.NO_NEXT_BLOCK)
        }

        val location = locator.locate(-1L)

        assertNull(location)
    }

    @Test fun testBlocksResolving() {
        val blockSize = 123
        val startOfBlock0 = 30000L
        val startOfBlock1 = 50000L
        val startOfBlock2 = 70000L
        val startOfBlock3 = 90000L

        val offsets = mapOf(startOfBlock0 to startOfBlock1,
                            startOfBlock1 to startOfBlock2,
                            startOfBlock2 to startOfBlock3)

        val locator = BlockLocator(startOfBlock0, blockSize) {
            BlockHeader(offsets[it] ?: BlockHeader.NO_NEXT_BLOCK)
        }

        val locationOfBlock0 = locator.locate(0L)
        assertEquals(startOfBlock0 + locator.blockHeaderSize, locationOfBlock0)

        val locationOfBlock1 = locator.locate(blockSize.toLong())
        assertEquals(startOfBlock1 + locator.blockHeaderSize, locationOfBlock1)

        val locationInBlock3 = locator.locate(3L * blockSize + 7)
        assertEquals(startOfBlock3 + locator.blockHeaderSize + 7, locationInBlock3)
    }

    @Test fun beyondLastBlock() {
        val blockSize = 123
        val startOfBlock0 = 0L
        val startOfBlock1 = 3000L
        val startOfBlock2 = 5000L
        val offsets = mapOf(startOfBlock0 to startOfBlock1,
                            startOfBlock1 to startOfBlock2)

        val locator = BlockLocator(0L, blockSize) {
            BlockHeader(offsets[it] ?: BlockHeader.NO_NEXT_BLOCK)
        }

        val target1 = blockSize * 2 + blockSize + 1L
        val target2 = blockSize * 5 + blockSize + 1L
        assertNull(locator.locate(target1))
        assertNull(locator.locate(target2))
    }
}