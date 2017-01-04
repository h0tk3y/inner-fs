import org.junit.jupiter.api.Assertions

internal inline fun <reified T : Throwable> assertThrows(noinline f: () -> Unit) {
    Assertions.assertThrows<T>(T::class.java, f)
}