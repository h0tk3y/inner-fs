import org.junit.Assert
import org.junit.Test

/**
 * Created by igushs on 12/23/16.
 */

class ByteStructuresTest {
    @Test fun blockHeader() {
        val blockHeader = BlockHeader(12345L)
        val bytes = blockHeader.bytes()

        Assert.assertEquals(BlockHeader.size, bytes.position())
        bytes.flip()

        val deserialized = BlockHeader.read(bytes)
        Assert.assertEquals(blockHeader, deserialized)
    }

    @Test fun directoryEntry() {
        val entry = DirectoryEntry(true, 12345L, "someMeaninglessName")
        val bytes = entry.bytes()

        Assert.assertEquals(DirectoryEntry.size, bytes.position())
        bytes.flip()

        val deserialized = DirectoryEntry.read(bytes)
        Assert.assertEquals(entry, deserialized)
    }
}