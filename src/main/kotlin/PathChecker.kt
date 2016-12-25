/**
 * Created by igushs on 12/21/16.
 */

const val maxSegmentLength = 256

internal object PathChecker {
    fun isValidSegment(segment: String) =
            segment.length <= maxSegmentLength
}