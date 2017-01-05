package com.github.h0tk3y.innerFs

import java.nio.file.FileSystemException

class FileIsInUseException(path: InnerPath, message: String) : FileSystemException(path.toString(), null, "$message: '$path' is in use")