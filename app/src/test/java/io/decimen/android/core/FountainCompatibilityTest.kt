package io.decimen.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FountainCompatibilityTest {
    @Test
    fun frameIndicesMatchJavaScriptReferenceVectors() {
        val vectors = listOf(
            Vector(1, 1, 0u, intArrayOf(0)),
            Vector(2, 42, 1u, intArrayOf(1)),
            Vector(7, 65535, 2u, intArrayOf(3, 0)),
            Vector(359, 12345, 0u, intArrayOf(176, 119, 165, 35, 219)),
            Vector(359, 12345, 1u, intArrayOf(337, 335)),
            Vector(359, 12345, 123456789u, intArrayOf(244, 130, 342, 187)),
            Vector(
                1432,
                54321,
                UInt.MAX_VALUE,
                intArrayOf(
                    866, 571, 688, 255, 1361, 1199, 342, 1254, 1000, 229, 673, 67,
                    1070, 1124, 307, 226, 134, 691, 190, 1198, 556, 536, 611, 798,
                    1145, 1160, 966, 1417, 1300, 1169, 1192, 321, 883, 295, 842,
                    783, 1049, 1351, 383, 1360, 268, 33, 39, 89, 1297, 410, 390, 1303,
                ),
            ),
        )

        for (vector in vectors) {
            assertArrayEquals(
                "k=${vector.k}, session=${vector.sessionId}, seq=${vector.sequence}",
                vector.expected,
                FountainMath.frameIndices(vector.k, vector.sessionId, vector.sequence),
            )
        }
    }

    @Test
    fun splitMixMatchesJavaScriptReference() {
        val random = SplitMix32(0x12345678)
        val expected = listOf(2986037511u, 744488920u, 2204577711u, 2810942300u, 1174022055u)
        expected.forEach { assertEquals(it, random.nextUInt()) }
    }

    @Test
    fun fountainRoundTripSurvivesDroppedFrames() {
        val payload = ByteArray(37_123) { index -> ((index * 31 + index / 7) and 0xFF).toByte() }
        val encoder = LTEncoder(payload, blockLength = 733, sessionId = 4242)
        val decoder = LTDecoder(encoder.blockCount, 733, 4242, payload.size)

        var sequence = 0u
        while (!decoder.isComplete && sequence < 20_000u) {
            if (sequence % 7u != 0u && sequence % 13u != 0u) {
                decoder.addFrame(sequence, encoder.encode(sequence))
            }
            sequence++
        }

        assertTrue("decoder did not complete", decoder.isComplete)
        assertArrayEquals(payload, decoder.assemble())
    }

    @Test
    fun protocolPackParseRoundTrip() {
        val block = ByteArray(733) { it.toByte() }
        val header = DecimenProtocol.FrameHeader(
            sessionId = 4242,
            sequence = UInt.MAX_VALUE,
            blockCount = 2,
            blockLength = 733,
            totalLength = 1000,
            payloadFnv = 0xAABBCCDDu,
        )
        val parsed = DecimenProtocol.parseFrame(DecimenProtocol.packFrame(header, block))
        assertNotNull(parsed)
        assertEquals(header, parsed!!.header)
        assertArrayEquals(block, parsed.block)
    }

    private data class Vector(
        val k: Int,
        val sessionId: Int,
        val sequence: UInt,
        val expected: IntArray,
    )
}
