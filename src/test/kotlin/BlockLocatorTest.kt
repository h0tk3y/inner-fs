import org.junit.Assert
import org.junit.Test

/**
 * Created by igushs on 12/23/16.
 */

class BlockLocatorTest {
    @Test fun empty() {
        val map = BlockLocator(BlockHeader.NO_NEXT_BLOCK) { throw NotImplementedError() }
        val l = map.locate(map.blockSize.toLong() / 2)

        Assert.assertNull(l)
    }

    @Test fun negativeLocation() {
        val offsets = mapOf(0L to 1000L,
                            1000L to 2000L,
                            2000L to 3000L)

        val locator = BlockLocator(0L, 1000) {
            BlockHeader(offsets[it] ?: BlockHeader.NO_NEXT_BLOCK)
        }

        val location = locator.locate(-1L)

        Assert.assertNull(location)
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
        Assert.assertEquals(startOfBlock0, locationOfBlock0)

        val locationOfBlock1 = locator.locate(blockSize.toLong())
        Assert.assertEquals(startOfBlock1, locationOfBlock1)

        val locationInBlock3 = locator.locate(3L * blockSize + 7)
        Assert.assertEquals(startOfBlock3 + 7, locationInBlock3)
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
        Assert.assertNull(locator.locate(target1))
        Assert.assertNull(locator.locate(target2))
    }
}