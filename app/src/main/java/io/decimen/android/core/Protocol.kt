package io.decimen.android.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Binary protocol used by decimen-optical-transfer (Wire Version 3 with v1/v2 compatibility). */
object DecimenProtocol {
    const val HEADER_LENGTH_V1 = 20
    const val HEADER_LENGTH_V3 = 22
    const val HEADER_LENGTH = HEADER_LENGTH_V3 // Default to v3

    const val MAGIC_0: Int = 0xD1
    const val MAGIC_1_V1: Int = 0x0C
    const val MAGIC_1_V2: Int = 0x0D
    const val MAGIC_1_V3: Int = 0xC3
    const val WIRE_VERSION: Int = 3

    // Deliberate Android safety limits, supporting up to 64 MB
    const val MAX_BLOCK_LENGTH = 4096
    const val MAX_BLOCK_COUNT = 32768
    const val MAX_PAYLOAD_LENGTH: Long = 64L * 1024L * 1024L

    data class FrameHeader(
        val sessionId: Int,
        val sequence: UInt,
        val blockCount: Int,
        val blockLength: Int,
        val totalLength: Long,
        val payloadFnv: UInt,
        val version: Int = WIRE_VERSION,
        val flags: Int = 0,
    )

    data class ParsedFrame(
        val header: FrameHeader,
        val block: ByteArray,
    )

    fun parseFrame(bytes: ByteArray): ParsedFrame? {
        if (bytes.size <= HEADER_LENGTH_V1) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 != MAGIC_0) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val (header, headerLen) = when {
            // Wire Version 3 (22 bytes header)
            b1 == MAGIC_1_V3 -> {
                if (bytes.size <= HEADER_LENGTH_V3) return null
                val version = bytes[2].toInt() and 0xFF
                val flags = bytes[3].toInt() and 0xFF
                // Critical flags check (lowest 4 bits: only 0x00 is currently supported)
                if ((flags and 0x0F) != 0) return null

                val hdr = FrameHeader(
                    version = version,
                    flags = flags,
                    sessionId = buffer.getShort(4).toInt() and 0xFFFF,
                    sequence = buffer.getInt(6).toUInt(),
                    blockCount = buffer.getShort(10).toInt() and 0xFFFF,
                    blockLength = buffer.getShort(12).toInt() and 0xFFFF,
                    totalLength = buffer.getInt(14).toUInt().toLong(),
                    payloadFnv = buffer.getInt(18).toUInt(),
                )
                hdr to HEADER_LENGTH_V3
            }
            // Legacy Wire Version 1 / 2 (20 bytes header)
            b1 == MAGIC_1_V1 || b1 == MAGIC_1_V2 -> {
                val hdr = FrameHeader(
                    version = if (b1 == MAGIC_1_V1) 1 else 2,
                    flags = 0,
                    sessionId = buffer.getShort(2).toInt() and 0xFFFF,
                    sequence = buffer.getInt(4).toUInt(),
                    blockCount = buffer.getShort(8).toInt() and 0xFFFF,
                    blockLength = buffer.getShort(10).toInt() and 0xFFFF,
                    totalLength = buffer.getInt(12).toUInt().toLong(),
                    payloadFnv = buffer.getInt(16).toUInt(),
                )
                hdr to HEADER_LENGTH_V1
            }
            else -> return null
        }

        if (header.blockCount == 0 || header.blockLength == 0 || header.totalLength == 0L) return null
        if (header.blockCount > MAX_BLOCK_COUNT) return null
        if (header.blockLength > MAX_BLOCK_LENGTH || header.totalLength > MAX_PAYLOAD_LENGTH) return null
        if (bytes.size != headerLen + header.blockLength) return null

        return ParsedFrame(
            header = header,
            block = bytes.copyOfRange(headerLen, bytes.size),
        )
    }

    fun packFrame(header: FrameHeader, block: ByteArray): ByteArray {
        require(header.sessionId in 0..0xFFFF)
        require(header.blockCount in 1..0xFFFF)
        require(header.blockLength in 1..0xFFFF)
        require(header.totalLength in 1..0xFFFF_FFFFL)
        require(block.size == header.blockLength)

        return ByteBuffer.allocate(HEADER_LENGTH_V3 + block.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(MAGIC_0.toByte())
                put(MAGIC_1_V3.toByte())
                put(WIRE_VERSION.toByte())
                put(header.flags.toByte())
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
