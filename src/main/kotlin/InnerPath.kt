import com.github.h0tk3y.kotlinFun.lexicographically
import java.io.File
import java.net.URI
import java.nio.file.*
import kotlin.comparisons.naturalOrder

/**
 * Created by igushs on 12/21/16.
 */

fun requireInnerFsPath(p: Path?): InnerPath =
        requireNotNull(p) as? InnerPath ?: throw IncompatiblePathException(p)

data class InnerPath(val innerFs: InnerFileSystem,
                     val pathSegments: List<String>) : Path {

    //region Transformation

    override fun toFile(): File = throw UnsupportedOperationException()

    override fun toUri() = URI(
            "ifs",
            "${innerFs.underlyingPath.toUri()}!${toAbsolutePath().pathSegments.joinToString(fileSystem.separator)}",
            null)

    override fun subpath(beginIndex: Int, endIndex: Int): Path =
            InnerPath(innerFs, pathSegments.subList(beginIndex, endIndex))

    override fun getFileName(): Path = InnerPath(innerFs, pathSegments.takeLast(1))

    override fun getName(index: Int): Path = InnerPath(innerFs, listOf(pathSegments[index]))

    override fun relativize(other: Path?): InnerPath {
        val p = requireInnerFsPath(other)
        if (isAbsolute xor other!!.isAbsolute)
            throw IllegalArgumentException()
        val commonParts = pathSegments.zip(p.pathSegments).takeWhile { (a, b) -> a == b }.count()
        return InnerPath(innerFs, p.pathSegments.drop(commonParts))
    }

    override fun toRealPath(vararg options: LinkOption?): Path = TODO()

    override fun toAbsolutePath(): InnerPath = if (isAbsolute)
        this else
        InnerPath(innerFs, listOf("") + pathSegments)

    override fun getParent(): InnerPath? = when {
        this == root -> this
        !this.isAbsolute && pathSegments.size == 1 -> null
        else -> InnerPath(innerFs, pathSegments.dropLast(1))
    }

    override fun getRoot(): Path? = if (isAbsolute)
        InnerPath(innerFs, listOf(pathSegments[0])) else
        null

    //endregion Transformation

    //region Resolution

    private val emptyPath by lazy { InnerPath(innerFs, emptyList()) }

    override fun resolveSibling(other: Path?): Path {
        val p = requireInnerFsPath(other)
        return when {
            p.isAbsolute || parent == null -> p
            p.pathSegments.isEmpty() -> parent ?: emptyPath
            else -> parent?.resolve(other) ?: emptyPath
        }
    }

    override fun resolveSibling(other: String?): Path = resolveSibling(innerFs.getPath(other))

    override fun resolve(other: Path?): Path {
        val p = requireInnerFsPath(other)
        if (p.isAbsolute)
            return p
        return InnerPath(innerFs, pathSegments + p.pathSegments)
    }

    override fun resolve(other: String?): Path = resolve(innerFs.getPath(other))

    override fun normalize(): Path = InnerPath(innerFs, normalizedPathSegments(pathSegments))

    private fun normalizedPathSegments(pathSegments: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (s in pathSegments) when (s) {
            "." -> Unit
            ".." -> if (result.isNotEmpty())
                result.removeAt(result.lastIndex) else
                throw IllegalArgumentException()
            else -> result.add(s)
        }
        return result
    }

    //endregion Resolution

    //region Inspection

    override fun isAbsolute(): Boolean = pathSegments.isNotEmpty() && pathSegments[0].isEmpty()

    override fun endsWith(other: Path?): Boolean {
        val that = requireInnerFsPath(other)
        return pathSegments.takeLast(that.pathSegments.size) == that.pathSegments
    }

    override fun endsWith(other: String?): Boolean {
        return endsWith(innerFs.getPath(other))
    }

    override fun iterator(): MutableIterator<Path> = pathSegments.run {
        if (pathSegments[0].isEmpty()) drop(1) else this
    }.mapTo(mutableListOf()) { innerFs.getPath(it) }.iterator()

    override fun getNameCount(): Int = pathSegments.size

    override fun startsWith(other: Path?): Boolean {
        val p = requireInnerFsPath(other)
        return pathSegments.take(p.pathSegments.size) == p.pathSegments
    }

    override fun startsWith(other: String?): Boolean = startsWith(innerFs.getPath(other))

    override fun compareTo(other: Path?): Int {
        val p = requireInnerFsPath(other)
        return lexicographicallyByNaturalOrder.compare(pathSegments, p.pathSegments)
    }

    override fun getFileSystem(): InnerFileSystem = innerFs

    //endregion

    override fun register(watcher: WatchService?,
                          events: Array<out WatchEvent.Kind<*>>?,
                          vararg modifiers: WatchEvent.Modifier?) =
            throw UnsupportedOperationException()

    override fun register(watcher: WatchService?, vararg events: WatchEvent.Kind<*>?): WatchKey =
            throw UnsupportedOperationException()

}

private val lexicographicallyByNaturalOrder = naturalOrder<String>().lexicographically()

class IncompatiblePathException(val otherPath: Path?)
    : IllegalAccessException("The path '$otherPath' is incompatible with InnerFS.")