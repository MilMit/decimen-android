package io.decimen.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Binary protocol used by the original decimen-optical-transfer web app. */
object DecimenProtocol {
    const val HEADER_LENGTH = 20
    const val MAGIC_0: Int = 0xD1
    const val MAGIC_1: Int = 0x0C

    // Deliberate Android MVP safety limits. The web protocol itself uses u16/u32 fields.
    const val MAX_BLOCK_LENGTH = 4096
    const val MAX_BLOCK_COUNT = 8192
    const val MAX_PAYLOAD_LENGTH: Long = 32L * 1024L * 1024L

    data class FrameHeader(
        val sessionId: Int,
        val sequence: UInt,
        val blockCount: Int,
        val blockLength: Int,
        val totalLength: Long,
        val payloadFnv: UInt,
    )

    data class ParsedFrame(
        val header: FrameHeader,
        val block: ByteArray,
    )

    fun parseFrame(bytes: ByteArray): ParsedFrame? {
        if (bytes.size <= HEADER_LENGTH) return null
        if ((bytes[0].toInt() and 0xFF) != MAGIC_0 || (bytes[1].toInt() and 0xFF) != MAGIC_1) {
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val header = FrameHeader(
            sessionId = buffer.getShort(2).toInt() and 0xFFFF,
            sequence = buffer.getInt(4).toUInt(),
            blockCount = buffer.getShort(8).toInt() and 0xFFFF,
            blockLength = buffer.getShort(10).toInt() and 0xFFFF,
            totalLength = buffer.getInt(12).toUInt().toLong(),
            payloadFnv = buffer.getInt(16).toUInt(),
        )

        if (header.blockCount == 0 || header.blockLength == 0 || header.totalLength == 0L) return null
        if (header.blockCount > MAX_BLOCK_COUNT) return null
        if (header.blockLength > MAX_BLOCK_LENGTH || header.totalLength > MAX_PAYLOAD_LENGTH) return null
        if (bytes.size != HEADER_LENGTH + header.blockLength) return null

        val expectedBlocks = maxOf(1, ceil(header.totalLength.toDouble() / header.blockLength).toInt())
        if (header.blockCount != expectedBlocks) return null

        return ParsedFrame(
            header = header,
            block = bytes.copyOfRange(HEADER_LENGTH, bytes.size),
        )
    }

    fun packFrame(header: FrameHeader, block: ByteArray): ByteArray {
        require(header.sessionId in 0..0xFFFF)
        require(header.blockCount in 1..0xFFFF)
        require(header.blockLength in 1..0xFFFF)
        require(header.totalLength in 1..0xFFFF_FFFFL)
        require(block.size == header.blockLength)

        return ByteBuffer.allocate(HEADER_LENGTH + block.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC_0.toByte())
                put(MAGIC_1.toByte())
                putShort(header.sessionId.toShort())
                putInt(header.sequence.toInt())
                putShort(header.blockCount.toShort())
                putShort(header.blockLength.toShort())
                putInt(header.totalLength.toUInt().toInt())
                putInt(header.payloadFnv.toInt())
                put(block)
            }
            .array()
    }

    fun fnv1a(bytes: ByteArray): UInt {
        var hash = 0x811C9DC5u
        for (byte in bytes) {
            hash = (hash xor (byte.toInt() and 0xFF).toUInt()) * 0x01000193u
        }
        return hash
    }
}

/** splitmix32, with the same 32-bit overflow semantics as the TypeScript source. */
internal class SplitMix32(seed: Int) {
    private var state: Int = seed

    fun nextUInt(): UInt {
        state += 0x9E3779B9u.toInt()
        var value = state xor (state ushr 16)
        value *= 0x21F0AAAD
        value = value xor (value ushr 15)
        value *= 0x735A2D97
        value = value xor (value ushr 15)
        return value.toUInt()
    }
}
