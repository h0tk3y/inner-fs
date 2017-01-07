package com.github.h0tk3y.innerFs.dsl

import com.github.h0tk3y.innerFs.InnerFileSystem
import com.github.h0tk3y.innerFs.InnerFileSystem.CreateMode.CREATE_OR_OPEN
import com.github.h0tk3y.innerFs.InnerFileSystemProvider
import com.github.h0tk3y.innerFs.InnerPath
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths

class InnerFileSystemDslContext(val here: InnerPath) {
    fun directory(name: String, initialize: InnerFileSystemDslContext.() -> Unit): InnerPath {
        val directoryPath = here.resolve(name)
        here.innerFs.createDirectory(directoryPath, failIfExists = false)
        initialize(InnerFileSystemDslContext(directoryPath))
        return directoryPath
    }

    fun file(name: String, initialize: FileChannel.() -> Unit): InnerPath {
        val path = InnerPath(here.innerFs, listOf(name))
        return file(path, initialize)
    }

    fun file(filePath: InnerPath, initialize: FileChannel.() -> Unit): InnerPath {
        require(filePath.innerFs == here.innerFs)
        val path = here.resolve(filePath)
        here.innerFs.openFile(path, read = false, write = true, create = CREATE_OR_OPEN).use {
            initialize(it)
        }
        return path
    }
}

fun innerFs(path: Path, initialize: InnerFileSystemDslContext.() -> Unit): InnerFileSystem {
    val fs = InnerFileSystemProvider().getOrCreateFileSystem(path)
    val context = InnerFileSystemDslContext(fs.rootDirectories.single())
    initialize(context)
    return fs
}

fun innerFs(pathString: String, initialize: InnerFileSystemDslContext.() -> Unit) = innerFs(Paths.get(pathString), initialize)