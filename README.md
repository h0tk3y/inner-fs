# inner-fs
A basic implementation of an in-file file system with Java NIO file system interface in Kotlin

## Design

The design is quite simple and straightforward: all the data is arranged in *blocks*, and there's even no 
special *master* or *root* records or anything similar to what you can see in popular file systems.

Blocks form linked lists: each block points to the next block (if any) of the file or directory which it belongs to, 
this *next block location* is stored inside the block's *header* (in its beginning). The header is followed by the *data*.

        block                   block

    +------------+   next   +------------+   next
    |   header   x--------->|   header   x---------> NO_NEXT_BLOCK
    +------------+          +------------+   
    |            |          |            |
    |    data    |          |    data    |
    |            |          |            |
    +------------+          +------------+
    
The directories are no different, only their content has special format. 
In the *data* section of the directory blocks there are *directory entries* -- small fixed size sections, each representing a single
item in the directory, including the *first block location* of the item -- file or another directory.

    +----------------+     next     +----------------+     next
    |     header     x------------->|     header     x-------------> NO_NEXT_BLOCK
    +----------------+              +----------------+                  
    |     entry      x----------+   |     entry      x-------------> NO_NEXT_BLOCK (no first block in this case)
    |----------------|          |   |----------------|     first 
    |      ...       |  first   |   |      ...       |
    |----------------|          |   |----------------|
    |     entry      x--+       |   |     entry      x--+ first
    +----------------+  |       |   +----------------+  |
                        |       |                       |
                        v       v                       v
                      block   block                   block
 
Directory entries store the file/directory name, the file size, the flag for directories and the already mentioned first block location.
    
An entry can point to `NO_NEXT_BLOCK` as its *first block location*, and in this case the entry doesn't correspond 
to any existing file system object, but can be re-used to store a new entry in that directory.

The *root* directory starts directly at zero offset in the file, and it's just a normal directory, but it stores the only 
special construct in InnerFS -- the *unallocated blocks entry*. This entry, just like any other, points to a first block in a linked 
list of blocks, but these blocks are *free*. If a file or a directory is deleted, all of its blocks are added to the unallocated blocks.
If a new file/directory is created or an existing file/directory requires more spaces, blocks are first taken from the unallocated blocks 
list and, only if there are none, new blocks are appended to the end of the underlying file.

## Interface

InnerFS implements the Java NIO2 File System SPI and thus operates with the generally used `Path` and `Channel`. Most of the operations
from the `Files` utils can be applied to InnerFS, too; the `SeekableByteChannel`s and `FileChannel`s provided by InnerFS are interoperable
with all the rest of Java NIO.

The URIs of InnerFS paths have the following form:

    ifs:[underlying URI]!/some/path/inside/ifs
    
where `[underlying URI]` is the URI of the underlying file, e.g. `file:///C/myFileSystem.ifs`.

The InnerFS file system provider is [installed as a service](https://docs.oracle.com/javase/7/docs/api/java/nio/file/FileSystems.html), 
and the instances of InnerFS can be obtained through `FileSystems.newFileSystem(uri, map)` and `FileSystems.getFileSystem(uri)` as well as
from an instance of `InnerFileSystemProvider` or even through `Paths.get(...)`. The simplest way:

    val provider = InnerFileSystemProvider()
    val ifs = provider.newFileSystem(path)
    
    // that's it, use it:
    Files.createDirectories(ifs.getPath("/a/b/c"))
    val channel = Files.newByteChannel(ifs.getPath("/a/b/c/1.txt"), CREATE, WRITE)

## Thread safety

The approach to safe concurrent operations that InnerFS takes is separate locks for blocks corresponding to the file system objects. For simplicity, only the first block of a file or a directory is locked, that is, such a block represents the whole file or directory. The operations lock the blocks either for *read* or for *write* with the well-known semantics (reads are done concurrently with other reads; writes lock the resource exclusively). Here are some statements about the locks:

* `FileChannel` read operations take only *read* lock of the file.
* `FileChannel` write operations take the *write* lock of the file and, if its size can change during the operation, the file's parent directory *write* lock.
* `allocateBlock` and `deallocateBlock` internal operations take *write* lock of the special `UNALLOCATED_BLOCKS` block, not the root block. This is done to avoid the lock ordering interference (see below) with the other operations.
* `openFile` (which creates files as well) takes *write* lock of the parent directory.
* `deleteFile` takes *write* locks of both parent directory and the file itself. Also, deleting and opening/creating files are atomic operations with respect to each other.
* locating a path is not atomic on the whole, but each of the steps into the directories along the path is; a deleted file will never be located: a locating step and deleting the file both take the locks on the parent directory, and, what's equally important, a non-empty directory cannot be deleted. However, renaming or moving a directory can interfere with the locating operation, in this case it can end up finding a file by its old path.
* `move` operation with `ATOMIC_MOVE` option first take the two *write* locks of the source and target parent directories and then performs the move, so that no other operation can be performed which involve the two parent directories.

To avoid deadlocks, InnerFS uses locks ordering. The order is, as follows:

* If block *a* represents a parent or an ancestor directory of file system entry *b*, then *a* cannot be locked while *b* is already locked. The relation *'represents a parent or an ancestor'* can change for two blocks *a* and *b* over time, but this can only happen if one of them is deallocated and then allocated again. This cannot happen inside a single lock on both of the blocks.

* After `UNALLOCATED_BLOCKS` is locked, no lock for any other file system object can be taken until `UNALLOCATED_BLOCK` is unlocked.

A simplified locks ordering hierarchy for a file system with items `root { a { c, d }, d }`:

              root
             /    \ 
            a      b
           / \     |
          c   d    |
          |   |    |
       UNALLOCATED_BLOCKS
      
## DSL

As many things in Kotlin, InnerFS comes with a DSL. It consists of two parts:

* declarative builder of a new InnerFS:

        val fileForLaterUse: Path? = null
        
        val ifs = innerFs("./test-inner-fs-${Random().nextLong()}.ifs") { // initializer for the whole file system
            directory("a") { // initializer for this directory
                directory("b") {
                    directory("c") {
                        file("a.txt") { } // initializer for a file
                        file("b.txt") { write(ByteBuffer.allocate(123)) }
                    }
                }
                fileForLaterUse = file("c.txt") { write(ByteBuffer.allocate(321)) } // the paths can be stored
                file("d.txt") { write(ByteBuffer.wrap("Hello".toByteArray())) }
            }
        }
        
* operators overloaded for better syntax of paths building:

        val ifs = someInnerFileSystem()
        
        val dir = ifs / "a" / "b" / "c"
        Files.createDirectories(dir)
        val file = dir / "1.txt"
