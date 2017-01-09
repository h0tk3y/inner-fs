# InnerFS
A basic implementation of an in-file file system with a Java NIO file system interface, written in Kotlin.

[![](https://img.shields.io/badge/kotlin-1.1--M04-blue.svg)](http://kotlinlang.org/) [![](https://jitpack.io/v/h0tk3y/inner-fs.svg)](https://jitpack.io/#h0tk3y/inner-fs) 

## How to use

InnerFS is currently built against and compatible with Kotlin 1.1-M04, and your project should use the same Kotlin version for compatibility.

Gradle dependency: 

    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }

    dependencies {
        ...
        compile 'com.github.h0tk3y:inner-fs:0.1'
    }
    
## Design

The design is quite simple and straightforward: all the data is arranged in *blocks*, and there's even no 
special *master* or *root* records or anything redundant for a simple file system.

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
If a new file/directory is created or an existing file/directory requires more space, blocks are first taken from the unallocated blocks 
list and, only if there are none, new blocks are appended to the end of the underlying file.

## Interface

InnerFS implements the Java NIO2 File System SPI and thus operates with the generally used `Path` and `Channel`. Most of the operations
from the `Files` utils can be applied to InnerFS, too; the `SeekableByteChannel`s and `FileChannel`s provided by InnerFS are interoperable
with most of the other Java NIO.

The URIs of InnerFS paths have the following form:

    ifs:[underlying URI]!/some/path/inside/ifs
    
where `[underlying URI]` is the URI of the underlying file, e.g. `file:///C:/myFileSystem.ifs`.

The InnerFS file system provider is [installed as a service](https://docs.oracle.com/javase/7/docs/api/java/nio/file/FileSystems.html), 
and the instances of InnerFS can be obtained through `FileSystems.newFileSystem(uri, map)` and `FileSystems.getFileSystem(uri)` as well as
from an instance of `InnerFileSystemProvider` or even through `Paths.get(...)`. The simplest way:

    val provider = InnerFileSystemProvider()
    val ifs = provider.newFileSystem(path)
    
    // that's it, use it:
    Files.createDirectories(ifs.getPath("/a/b/c"))
    val channel = Files.newByteChannel(ifs.getPath("/a/b/c/1.txt"), CREATE, WRITE)
     
As a alternative and a simpler way of working with InnerFS, you can use some of its methods directly, without using the NIO `Files` API. These methods are:

* `openFile(path, read = true, write = true, append = false, truncate = false, create = CREATE_OR_OPEN)`

* `createDirectory(path, failIfExists = true, createMissingDirectories = false)`
                       
* `delete(path)`

* `directorySequence(path)` (instead of `Files.newDirectoryStream(path)`)

* `move(from, to, replaceExisting = false)`

* `copy(from, to, replaceExisting = false)`

Also, for a simpler way of composing InnerFS paths for `Files` operations, please see the *DSL* section.

Feel free to see the tests for advanced usage examples.

## Thread safety

The approach to safe concurrent operations that InnerFS takes is separate locks for blocks corresponding to the file system objects. 
For simplicity, only the first block of a file or a directory is locked, that is, such a block represents the whole file or directory, so we can rather call it the file system objects locking.
 
The operations lock the blocks either for *read* or for *write* with the well-known semantics (reads are done concurrently with other reads; writes lock the resource exclusively). Here are some statements about the locks:

* `FileChannel` read operations take only *read* lock of the file.
* `FileChannel` write operations take the *write* lock of the file, but also rewrite its directory entry in the parent directory. 
 This is safe, because if the file is open, the entry cannot be removed, and it is enough to make all the changes to the entry under the file's lock.
* `allocateBlock` and `deallocateBlock` internal operations take *write* lock of the special `UNALLOCATED_BLOCKS` block, not the root block. This is done to avoid the lock ordering interference (see below) with the other operations.
* `openFile` (which creates files as well) takes *write* lock of the parent directory, and also atomically creates a file descriptor.
* `delete` takes *write* locks of both parent directory and the file itself. Deleting and opening/creating files are atomic operations with respect to each other.
* locating a path is not atomic on the whole, but each of the steps into the directories along the path is; a deleted file will never be located: a locating step and deleting the file both take the locks on the parent directory, and, what's equally important, a non-empty directory cannot be deleted. 
* `move` operation first take the two *write* locks of the source and target parent directories and then performs the move, so that no other operation can be performed which involve the two parent directories.

Apart from the file system objects, there is a `openFileDescriptors` lock that protects concurrent operations with the file descriptors.

To avoid deadlocks, InnerFS uses locks ordering. The imposed order is based on the paths comparing. The paths are 
compared lexicographically as lists of *path segments*, for example, `/abc/def` < `/abc/def/ghi`, and `/abc/def` < `/abc/xyz`.

* If block *a* represents a parent or an ancestor directory of file system entry *b*, then *a* cannot be locked while 
*b* is already locked. The relation *'represents a parent or an ancestor'* can change for two blocks *a* and *b* over 
time, but this can only happen if one of them is deallocated and then allocated again. This cannot happen inside a 
single lock on both of the blocks.

* In case neither of blocks *a* and *b* represents the other's parent or ancestor, consider *aPath* and *bPath* the 
paths that lead to the blocks *a* and *b* respectively. If `aPath < bPath` (lexicographically) then if *b* is blocked, 
*a* should not be blocked until *b* is unlocked.

* After `UNALLOCATED_BLOCKS` is locked, no lock for any other file system object can be taken until `UNALLOCATED_BLOCK` is unlocked.

* A lock for `openFileDescriptors` cannot be locked if `UNALLOCATED_BLOCKS` lock is taken, and while `openFileDescriptor`
 is locked, only the `UNALLOCATED_BLOCKS` lock can be taken, but no other locks.

An example locks ordering hierarchy for a file system (the arrows that come from the ordering transitivity are omitted):

              root
             /    \
            v      v
            a ---> b 
           / \    ^ |
          v   v   | |
         c --> d -+ |
         |     |    |
         v     v    v
      openFileDescriptors
               |
               v
      UNALLOCATED_BLOCKS
      
## DSL

As many things in Kotlin, InnerFS comes with a DSL. It consists of two parts:

* declarative builder of a new InnerFS or accessing an existing one:

        var fileForLaterUse: Path? = null
        
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

## Unsupported / Further work

* File system watching with `WatchService`s has not been implemented yet.

* Path matchers are not supported yet.

* `FileChannel` locks are not supported for the InnerFS file channels. This can be implemented by mapping the range locks to (possibly, multiple) underlying blocks locks.

* File attributes are currently supported in a limited way (only operations with `BasicFileAttributes` and `BasicFileAttributeView` are supported, but not the named attributes). 

* Directory entries are quite big (~301 byte) and are allocated with iteration through the whole directory to find a vacant place. This can be optimized. 

* Currently, the instances of InnerFS are identical with no possible customization. There is room for improvement in paremeterizing the file systems. What comes to mind first is setting `BLOCK_SIZE` for a file system, but several other things can be done, too. Anyway, this would rather require increasing design complexity than that of the implementation.
