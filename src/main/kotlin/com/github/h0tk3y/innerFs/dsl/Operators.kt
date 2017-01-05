package com.github.h0tk3y.innerFs.dsl

import com.github.h0tk3y.innerFs.InnerFileSystem
import com.github.h0tk3y.innerFs.InnerPath

operator fun InnerPath.div(nameSegment: String) = resolve(nameSegment)
operator fun InnerPath.div(anotherPath: InnerPath) = resolve(anotherPath)
operator fun InnerFileSystem.div(nameSegment: String) = rootDirectories.single().resolve(nameSegment)
operator fun InnerFileSystem.div(anotherPath: InnerPath) = rootDirectories.single().resolve(anotherPath)