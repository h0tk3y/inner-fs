package com.github.h0tk3y.innerFs.dsl

import com.github.h0tk3y.innerFs.InnerFileSystem
import com.github.h0tk3y.innerFs.InnerFileSystemProvider
import com.github.h0tk3y.innerFs.InnerPath
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.Paths

class InnerFileSystemDslContext(val path: InnerPath) {
    fun directory(name: String, initialize: InnerFileSystemDslContext.() -> Unit): InnerPath {
        val directoryPath = path.resolve(name)
        path.innerFs.createDirectory(directoryPath)
        initialize(InnerFileSystemDslContext(directoryPath))
        return directoryPath
    }

    fun file(name: String, initialize: FileChannel.() -> Unit): InnerPath {
        val filePath = path.resolve(name)
        val channel = path.innerFs.openFile(filePath, read = false, write = true)
        initialize(channel)
        return filePath
    }
}

fun innerFs(path: Path, initialize: InnerFileSystemDslContext.() -> Unit): InnerFileSystem {
    val fs = InnerFileSystemProvider().getOrCreateFileSystem(path)
    val context = InnerFileSystemDslContext(fs.rootDirectories.single())
    initialize(context)
    return fs
}

fun innerFs(pathString: String, initialize: InnerFileSystemDslContext.() -> Unit) = innerFs(Paths.get(pathString), initialize)