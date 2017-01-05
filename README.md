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

(there is some, but this section is to be written)

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
