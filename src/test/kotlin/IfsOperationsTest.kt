
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Created by igushs on 12/25/16.
 */

class IfsOperationsTest {
    private val provider = InnerFileSystemProvider.instance

    lateinit var ifs: InnerFileSystem

    @Before fun initIfs() {
        val file = Files.createTempFile(Paths.get("."), "innerFs", ".ifs")
        Files.delete(file)
        ifs = InnerFileSystemProvider.instance.newFileSystem(file, emptyMap<String, Unit>()) as InnerFileSystem
    }

    @After fun closeFs() {
        ifs.close()
        Files.delete(ifs.underlyingPath)
    }

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
        val freeBlock = negativeTransform(ifs.getFreeBlocks().entry.firstBlockLocation)
        assertEquals(allocatedBlock, freeBlock)

        val allocatedAgain = ifs.allocateBlock { }
        assertEquals(allocatedBlock, allocatedAgain)
    }

    @Test fun freeBlocksChain() {

    }
}