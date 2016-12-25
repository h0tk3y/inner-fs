class BlockLocator(initialBlockLocation: Long,
                   val blockSize: Int = BLOCK_SIZE,
                   val blockReader: (offset: Long) -> BlockHeader) {

    //Stores starts of blocks
    private val locationList = mutableListOf(initialBlockLocation)

    private fun blockIndexByTarget(target: Long) = target / blockSize

    private fun offsetInBlock(target: Long) = target % blockSize

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

        return blockLocation + offsetInBlock(target)
    }
}