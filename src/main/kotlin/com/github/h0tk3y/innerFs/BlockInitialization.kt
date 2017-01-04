package com.github.h0tk3y.innerFs

import java.nio.ByteBuffer

internal const val EMPTY_ENTRY_NAME = "---"

internal val initializeDirectoryBlock = { dataBuffer: ByteBuffer ->
    val emptyEntry = DirectoryEntry(false, BlockHeader.NO_NEXT_BLOCK, -1, EMPTY_ENTRY_NAME)
    for (i in 1..entriesInBlock)
        emptyEntry.writeTo(dataBuffer)
}

internal val initializeDataBlock: (ByteBuffer) -> Unit = { dataBuffer: ByteBuffer ->
    dataBuffer.put(ByteBuffer.allocateDirect(dataBytesInBlock))
}