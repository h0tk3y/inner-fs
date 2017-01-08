package com.github.h0tk3y.innerFs
import sun.nio.fs.DefaultFileSystemProvider
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider

/**
 * Created by igushs on 12/21/16.
 */


private val createdFileSystems = mutableMapOf<Path, InnerFileSystem>()

class InnerFileSystemProvider : FileSystemProvider() {

    //region Java NIO SPI

    override fun getScheme(): String = "ifs"

    fun newFileSystem(uri: URI?) = newFileSystem(uri, emptyMap<String, Any>())

    override fun newFileSystem(uri: URI?, env: Map<String, *>?) = newFileSystem(DefaultFileSystemProvider.create().getPath(uri), env)

    fun newFileSystem(path: Path) = newFileSystem(path, emptyMap<String, Any>())

    override fun newFileSystem(path: Path, env: Map<String, *>?): InnerFileSystem {
        synchronized(createdFileSystems) {
            val normalizedAbsolutePath = path.toAbsolutePath().normalize()
            val existingFs = createdFileSystems[normalizedAbsolutePath]
            if (existingFs != null && existingFs.isOpen)
                throw FileSystemAlreadyExistsException()

            val fs = InnerFileSystem(normalizedAbsolutePath, !Files.exists(path))
            createdFileSystems[normalizedAbsolutePath] = fs
            return fs
        }
    }

    override fun getFileSystem(uri: URI?): InnerFileSystem? =
            synchronized(createdFileSystems) {
                val path = Paths.get(uri)?.toAbsolutePath()?.normalize() ?: return@synchronized null
                createdFileSystems[path] ?: throw FileSystemNotFoundException()
            }

    fun getOrCreateFileSystem(path: Path): InnerFileSystem {
        synchronized(createdFileSystems) {
            return try {
                getFileSystem(path.toUri())
            } catch (_: FileSystemNotFoundException) {
                null
            } ?: newFileSystem(path, emptyMap<String, Any>())
        }
    }

    override fun getPath(uri: URI?): Path? {
        requireNotNull(uri)
        val schemeSpecificPart = uri!!.schemeSpecificPart
        val fsPath = schemeSpecificPart.substringBeforeLast("!/")
        if (fsPath == schemeSpecificPart)
            throw IllegalAccessException("The URI '$uri' is malformed. Correct URI: '$scheme:file:/c:/foo.ifs!/bar'.")
        val fs = synchronized(createdFileSystems) {
            getFileSystem(URI.create(fsPath)) ?:
            newFileSystem(Paths.get(fsPath), emptyMap<String, Unit>())
        }
        return fs.getPath(schemeSpecificPart.substringAfterLast("!"))
    }

    override fun isSameFile(path: Path?, path2: Path?): Boolean {
        val p1 = requireInnerFsPath(path)
        val p2 = requireInnerFsPath(path2)
        if (p1 == p2)
            return true
        if (p1.innerFs == p2.innerFs) {
            checkAccess(p1)
            checkAccess(p2)
            return p1.normalize() == p2.normalize()
        }
        return false
    }

    override fun checkAccess(path: Path, vararg modes: AccessMode?) {
        val p = requireInnerFsPath(path)
        if (AccessMode.EXECUTE in modes)
            throw AccessDeniedException("InnerFS doesn't support AccessMode.EXECUTE")
        if (p == p.root)
            return //positively found
        p.innerFs.locateEntry(p) ?: throw NoSuchFileException("$path")
    }

    override fun isHidden(path: Path?): Boolean {
        val p = requireInnerFsPath(path)
        return p.fileNameString.startsWith(".")
    }

    override fun <V : FileAttributeView?> getFileAttributeView(path: Path?, type: Class<V>?, vararg options: LinkOption?): V {
        if (type != BasicFileAttributeView::class.java)
            throw UnsupportedOperationException()
        val p = requireInnerFsPath(path?.normalize())
        val (_, e) = p.innerFs.locateEntry(p) ?: throw NoSuchFileException("$path")
        @Suppress("UNCHECKED_CAST")
        return object : BasicFileAttributeView {
            override fun readAttributes(): BasicFileAttributes = object : BasicFileAttributes {
                override fun isOther() = false
                override fun isDirectory() = e.isDirectory
                override fun isSymbolicLink() = false
                override fun isRegularFile() = true
                override fun creationTime() = FileTime.fromMillis(e.createdTimeMillis)
                override fun size() = e.size
                override fun fileKey() = null
                override fun lastModifiedTime() = FileTime.fromMillis(if (isDirectory) 0L else e.lastModifiedTimeMillis)
                override fun lastAccessTime() = FileTime.fromMillis(if (isDirectory) 0L else e.lastAccessTimeMillis)
            }

            override fun setTimes(lastModifiedTime: FileTime?, lastAccessTime: FileTime?, createTime: FileTime?) =
                    throw UnsupportedOperationException()

            override fun name(): String = e.name
        } as V
    }

    override fun <A : BasicFileAttributes?> readAttributes(path: Path?, type: Class<A>?, vararg options: LinkOption?): A {
        if (type != BasicFileAttributes::class.java)
            throw UnsupportedOperationException("Attributes for $type are not supported, use BasicFileAttributes instead")
        @Suppress("UNCHECKED_CAST")
        return getFileAttributeView(path, BasicFileAttributeView::class.java).readAttributes() as A
    }

    override fun readAttributes(path: Path?, attributes: String?, vararg options: LinkOption?) =
            throw UnsupportedOperationException()

    override fun copy(source: Path?, target: Path?, vararg options: CopyOption?) {
        val s = requireInnerFsPath(source?.normalize())
        val p = requireInnerFsPath(target?.normalize())

        options.forEach {
            if (it != StandardCopyOption.REPLACE_EXISTING)
                throw UnsupportedOperationException("Unsupported option $it")
        }

        s.innerFs.copy(s, p, StandardCopyOption.REPLACE_EXISTING in options)
    }

    override fun move(source: Path?, target: Path?, vararg options: CopyOption?) {
        val s = requireInnerFsPath(source?.normalize())
        val p = requireInnerFsPath(target?.normalize())

        var replaceExisting = false
        var atomicMove = false

        for (o in options) when (o) {
            StandardCopyOption.REPLACE_EXISTING -> replaceExisting = true
            StandardCopyOption.ATOMIC_MOVE -> atomicMove = true
            else -> throw UnsupportedOperationException("Option $o is not supported")
        }

        s.innerFs.move(s, p, replaceExisting, atomicMove)
    }

    override fun delete(path: Path?) {
        val p = requireInnerFsPath(path)
        p.innerFs.deleteFile(p)
    }

    override fun newDirectoryStream(dir: Path?, filter: DirectoryStream.Filter<in Path>): DirectoryStream<Path> {
        val p = requireInnerFsPath(dir)
        val sequence = p.innerFs.directorySequence(p).filter { filter.accept(it) }
        return object : DirectoryStream<Path> {
            @Volatile var closed = false

            override fun close() {
                closed = true
            }

            var iteratorCalled = false
            lateinit var iterator: Iterator<Path>

            @Synchronized
            override fun iterator(): MutableIterator<Path> {
                check(!iteratorCalled)
                iteratorCalled = true
                iterator = sequence.iterator()
                return object : MutableIterator<Path> {
                    private var nextGuaranteed = false

                    override fun next(): Path = if (nextGuaranteed) iterator.next() else run { check(!closed); iterator.next() }
                    override fun hasNext(): Boolean = run { nextGuaranteed = !closed && iterator.hasNext(); nextGuaranteed }
                    override fun remove() = throw UnsupportedOperationException()
                }
            }
        }
    }

    override fun createDirectory(dir: Path?, vararg attrs: FileAttribute<*>?) {
        val p = requireInnerFsPath(dir)
        p.innerFs.createDirectory(p, failIfExists = true, createMissingDirectories = false)
    }


    override fun newByteChannel(path: Path?,
                                options: MutableSet<out OpenOption>,
                                vararg attrs: FileAttribute<*>?): SeekableByteChannel =
            newFileChannel(path, options, *attrs)

    override fun newFileChannel(path: Path?, options: MutableSet<out OpenOption>, vararg attrs: FileAttribute<*>?): FileChannel {
        if (attrs.isNotEmpty()) throw UnsupportedOperationException("File attributes are not supported.")

        var write = false
        var append = false
        var create = false
        var createNew = false
        var truncateExisting = false

        for (o in options) when (o) {
            StandardOpenOption.READ -> Unit
            StandardOpenOption.WRITE -> write = true
            StandardOpenOption.APPEND -> append = true
            StandardOpenOption.CREATE -> create = true
            StandardOpenOption.CREATE_NEW -> createNew = true
            StandardOpenOption.TRUNCATE_EXISTING -> truncateExisting = true
            else -> throw UnsupportedOperationException("Option $o is not supported.")
        }

        val p = requireInnerFsPath(path)
        return p.innerFs.openFile(path = p,
                                  read = true, // this is because of some methods in NIO relying on default readability
                                  write = write,
                                  append = append,
                                  truncateExisting = truncateExisting && write,
                                  create = when {
                                      createNew -> InnerFileSystem.CreateMode.CREATE_OR_FAIL
                                      create -> InnerFileSystem.CreateMode.CREATE_OR_OPEN
                                      else -> InnerFileSystem.CreateMode.OPEN_OR_FAIL
                                  })
    }

    override fun getFileStore(path: Path?): FileStore {
        return requireInnerFsPath(path).innerFs.fileStores.single()
    }

    override fun setAttribute(path: Path?, attribute: String?, value: Any?, vararg options: LinkOption?) {
        throw UnsupportedOperationException("Setting attributes is not supported for InnerFS.")
    }

//endregion Java NIO SPI

    override fun equals(other: Any?): Boolean {
        return other is InnerFileSystemProvider
    }

    override fun hashCode(): Int = javaClass.hashCode()
}