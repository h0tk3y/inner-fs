package com.github.h0tk3y.innerFs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files

/**
 * Created by igushs on 1/2/17.
 */

@ExtendWith(IfsExternalResource::class)
class DirectoriesTest {

    @InjectIfs
    lateinit var ifs: InnerFileSystem

    @Test fun createAndCheck() {
        Files.createDirectories(ifs.getPath("/a/b/c"))
        assertTrue(Files.isDirectory(ifs.getPath("/a/b/c")))
        val p1 = ifs.getPath("/a/b/c/1.txt")
        val p2 = ifs.getPath("/a/b/c/2.txt")
        Files.createFile(p1)
        Files.createFile(p2)
        val paths = Files.newDirectoryStream(ifs.getPath("/a/b/c")).toList()
        assertEquals(listOf(p1, p2), paths)
    }

    @Test fun deleteNonEmpty() {
        val directory = ifs.getPath("/a/b/c")
        Files.createDirectories(directory)
        val fileInDirectory = ifs.getPath("/a/b/c/1.txt")
        Files.createFile(fileInDirectory)
        assertThrows<DirectoryNotEmptyException> {
            Files.delete(directory)
        }
        Files.delete(fileInDirectory)
        assertFalse(Files.exists(fileInDirectory))
        Files.delete(directory)
        assertFalse(Files.exists(directory))
    }

    @Test fun multiblockDirectories() {
        val directory = ifs.getPath("/a/b/c")
        Files.createDirectories(directory)
        for (i in 1..entriesInBlock * 5) {
            val file = directory.resolve("$i.txt")
            Files.createFile(file)
        }
        val files = Files.newDirectoryStream(directory).map { (it as InnerPath).fileNameString }.toSet()
        assertEquals((1..entriesInBlock * 5).map { "$it.txt" }.toSet(), files)
    }

    @Test fun recyclingEntries() {
        val nItems = 2 * entriesInBlock

        val directory = ifs.getPath("/a/b/c")
        Files.createDirectories(directory)
        for (i in 1..nItems) {
            Files.createFile(directory.resolve("$i"))
        }
        val files1 = Files.newDirectoryStream(directory).map { (it as InnerPath).fileNameString }.toSet()
        assertEquals((1..nItems).map { "$it" }.toSet(), files1)

        for (i in 1..nItems / 2) {
            Files.delete(directory.resolve("${i * 2}"))
        }
        val files2 = Files.newDirectoryStream(directory).map { (it as InnerPath).fileNameString }.toSet()
        assertEquals((1..nItems / 2).map { "${it * 2 - 1}" }.toSet(), files2)

        for (i in 1..nItems / 2) {
            Files.createFile(directory.resolve("${i * 2} - new"))
        }
        val files3 = Files.newDirectoryStream(directory).map { (it as InnerPath).fileNameString }.toSet()
        assertEquals((1..nItems).map { if (it % 2 == 0) "$it - new" else "$it" }.toSet(), files3)
    }
}