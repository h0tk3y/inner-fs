
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * Created by igushs on 12/23/16.
 */

internal fun channelCopy(src: ReadableByteChannel, dest: WritableByteChannel) {
    val buffer = ByteBuffer.allocateDirect(4 * 1024)
    while (src.read(buffer) != -1) {
        buffer.flip()
        dest.write(buffer)
        buffer.compact()
    }
    buffer.flip()
    while (buffer.hasRemaining()) {
        dest.write(buffer)
    }
}

internal fun Boolean.asByte() = if (this) 1.toByte() else 0.toByte()
internal fun Byte.asBoolean() = this != 0.toByte()

internal fun negativeTransform(l: Long) = -1 * l - 1

internal inline fun <T> Iterable<T>.sumByLong(selector: (T) -> Long): Long {
    var sum = 0L
    for (element in this) {
        sum += selector(element)
    }
    return sum
}