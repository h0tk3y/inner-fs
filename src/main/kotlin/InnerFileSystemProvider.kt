import java.net.URI
import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.FileAttributeView
import java.nio.file.spi.FileSystemProvider

/**
 * Created by igushs on 12/21/16.
 */

class InnerFileSystemProvider private constructor() : FileSystemProvider() {

    companion object {
        val instance by lazy { InnerFileSystemProvider() }
    }

    private val createdFileSystems = mutableMapOf<Path, InnerFileSystem>()

    //region Java NIO SPI

    override fun checkAccess(path: Path?, vararg modes: AccessMode?) {
        TODO()
    }

    override fun copy(source: Path?, target: Path?, vararg options: CopyOption?) {
        if (options.isNotEmpty())
            throw UnsupportedOperationException("No options are supported.")

        copyFsEntry(source, target, false)
    }

    override fun move(source: Path?, target: Path?, vararg options: CopyOption?) {
        copyFsEntry(source, target, true)
        if (source != target)
            delete(source)
    }

    private fun copyFsEntry(source: Path?, target: Path?, copyDirectoryEntries: Boolean) {
        val s = requireInnerFsPath(source)
        val t = requireInnerFsPath(target)

        if (s.normalize() == t.normalize()) {
            return
        }

        if (Files.isDirectory(s) && !copyDirectoryEntries) {
            Files.createDirectory(t)
            return
        }

        Files.newByteChannel(s, StandardOpenOption.READ).use { input ->
            Files.newByteChannel(t, StandardOpenOption.CREATE_NEW).use { output ->
                channelCopy(input, output)
            }
        }
    }

    override fun <V : FileAttributeView?> getFileAttributeView(path: Path?, type: Class<V>?, vararg options: LinkOption?): V {
        TODO("not implemented")
    }

    override fun isSameFile(path: Path?, path2: Path?): Boolean =
            path?.normalize() == path2?.normalize()

    override fun newFileSystem(uri: URI?, env: Map<String, *>?) = TODO()

    override fun newFileSystem(path: Path?, env: Map<String, *>?): FileSystem {
        require(path?.fileSystem == FileSystems.getDefault()) {
            "A path from the default file system provider is required."
        }
        synchronized(createdFileSystems) {
            val fs = InnerFileSystem(path!!, this, !Files.exists(path))
            createdFileSystems[path] = fs
            return fs
        }
    }

    override fun getScheme(): String = "ifs"

    override fun isHidden(path: Path?): Boolean {
        requireNotNull(path)
        return path!!.fileName.startsWith(".")
    }

    override fun newDirectoryStream(dir: Path?, filter: DirectoryStream.Filter<in Path>?): DirectoryStream<Path> {
        TODO("not implemented")
    }

    override fun newByteChannel(path: Path?, options: MutableSet<out OpenOption>?, vararg attrs: FileAttribute<*>?): SeekableByteChannel {
        TODO("not implemented")
    }

    override fun delete(path: Path?) {
        TODO("not implemented")
    }

    override fun <A : BasicFileAttributes?> readAttributes(path: Path?, type: Class<A>?, vararg options: LinkOption?): A {
        TODO("not implemented")
    }

    override fun readAttributes(path: Path?, attributes: String?, vararg options: LinkOption?): MutableMap<String, Any> {
        TODO("not implemented")
    }

    override fun getFileSystem(uri: URI?): FileSystem? =
            synchronized(createdFileSystems) {
                val path = Paths.get(uri)?.toRealPath() ?: return@synchronized null
                createdFileSystems[path]
            }


    override fun getPath(uri: URI?): Path? {
        requireNotNull(uri)
        val schemeSpecificPart = uri!!.schemeSpecificPart
        val fsPath = schemeSpecificPart.substringBefore("!/")
        if (fsPath == schemeSpecificPart)
            throw IllegalAccessException("The URI '$uri' is malformed. Correct URI: '$scheme:file:/c:/foo.ifs!/bar'.")
        val fs = getFileSystem(uri) ?: newFileSystem(Paths.get(fsPath), emptyMap<String, Unit>())
        return fs.getPath(schemeSpecificPart.substringAfter("!/"))
    }

    override fun getFileStore(path: Path?): FileStore {
        return requireInnerFsPath(path).innerFs.fileStores.single()
    }

    override fun setAttribute(path: Path?, attribute: String?, value: Any?, vararg options: LinkOption?) {
        throw UnsupportedOperationException("Setting attributes is not supported for InnerFS.")
    }


    override fun createDirectory(dir: Path?, vararg attrs: FileAttribute<*>?) {
        TODO("not implemented")
    }

    //endregion Java NIO SPI

}