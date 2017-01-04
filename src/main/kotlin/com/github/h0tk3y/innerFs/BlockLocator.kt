package com.github.h0tk3y.innerFs

internal class BlockLocator(initialBlockLocation: Long,
                            val blockDataSize: Int = dataBytesInBlock,
                            val blockHeaderSize: Int = BlockHeader.size,
                            val blockReader: (offset: Long) -> BlockHeader) {

    //Stores starts of blocks
    private val locationList = mutableListOf(initialBlockLocation)

    private fun blockIndexByTarget(target: Long) = target / blockDataSize

    private fun offsetInBlockData(target: Long) = (target % blockDataSize).toInt()

    @Synchronized
    fun locate(target: Long): Long? {
        if (target < 0)
            return null

        val targetBlock = blockIndexByTarget(target)

        while (targetBlock > locationList.lastIndex &&
               locationList.last() != BlockHeader.NO_NEXT_BLOCK) {
            val nextBlockLocation = blockReader(locationList.last()).nextBlockLocation
            locationList.add(nextBlockLocation)
        }

        if (targetBlock !in locationList.indices)
            return null

        val blockLocation = locationList[targetBlock.toInt()]

        if (blockLocation == BlockHeader.NO_NEXT_BLOCK)
            return null

        return blockLocation + blockHeaderSize + offsetInBlockData(target)
    }

    fun appendBlock(blockLocation: Long) {
        check(locationList.last() == BlockHeader.NO_NEXT_BLOCK) { "Appending a block is only allowed if the last block is known" }
        locationList.removeAt(locationList.lastIndex)
        locationList.add(blockLocation)
    }

    fun remainingBytesInBlock(target: Long): Int = dataBytesInBlock - (target % dataBytesInBlock).toInt()

    val lastBlockLocation get() = locationList.last { it != BlockHeader.NO_NEXT_BLOCK }
}