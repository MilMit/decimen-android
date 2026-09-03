package io.decimen.android.core

fun main() {
    val vectors = listOf(
        Triple(1, 1, 0u) to intArrayOf(0),
        Triple(2, 42, 1u) to intArrayOf(1),
        Triple(7, 65535, 2u) to intArrayOf(3, 0),
        Triple(359, 12345, 0u) to intArrayOf(176, 119, 165, 35, 219),
        Triple(359, 12345, 1u) to intArrayOf(337, 335),
        Triple(359, 12345, 123456789u) to intArrayOf(244, 130, 342, 187),
    )
    for ((input, expected) in vectors) {
        val actual = FountainMath.frameIndices(input.first, input.second, input.third)
        check(actual.contentEquals(expected)) {
            "Mismatch for $input: ${actual.joinToString()} != ${expected.joinToString()}"
        }
    }

    val random = SplitMix32(0x12345678)
    val expectedRandom = listOf(2986037511u, 744488920u, 2204577711u, 2810942300u, 1174022055u)
    for (expected in expectedRandom) check(random.nextUInt() == expected)

    val payload = ByteArray(37_123) { index -> ((index * 31 + index / 7) and 0xFF).toByte() }
    val sessionId = 4242
    val encoder = LTEncoder(payload, 733, sessionId)
    val decoder = LTDecoder(encoder.blockCount, 733, sessionId, payload.size)
    var sequence = 0u
    while (!decoder.isComplete && sequence < 20_000u) {
        // Deterministic loss/reordering simulation: skip some frames.
        if (sequence % 7u != 0u && sequence % 13u != 0u) {
            decoder.addFrame(sequence, encoder.encode(sequence))
        }
        sequence++
    }
    check(decoder.isComplete) { "Decoder did not complete" }
    check(decoder.assemble()!!.contentEquals(payload)) { "Decoded payload mismatch" }
    check(DecimenProtocol.fnv1a(decoder.assemble()!!) == DecimenProtocol.fnv1a(payload))

    val header = DecimenProtocol.FrameHeader(
        sessionId = sessionId,
        sequence = 99u,
        blockCount = encoder.blockCount,
        blockLength = 733,
        totalLength = payload.size.toLong(),
        payloadFnv = DecimenProtocol.fnv1a(payload),
    )
    val packed = DecimenProtocol.packFrame(header, encoder.encode(99u))
    val parsed = checkNotNull(DecimenProtocol.parseFrame(packed))
    check(parsed.header == header)
    check(parsed.block.contentEquals(encoder.encode(99u)))

    println("PASS: Kotlin fountain/protocol core matches JavaScript vectors and round-trip test")
    println("framesNew=${decoder.framesNew}, solved=${decoder.solvedCount}/${decoder.blockCount}")
}
