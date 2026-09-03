package io.decimen.android.core

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Exact Kotlin port of shared/fountain.ts from decimen-optical-transfer. */
object FountainMath {
    private const val LN2 = 0.6931471805599453
    private const val SOLITON_C = 0.1
    private const val SOLITON_DELTA = 0.5

    internal fun deterministicLog(x: Double): Double {
        require(x > 0.0)
        var exponent = 0
        var mantissa = x
        while (mantissa >= 1.5) {
            mantissa /= 2.0
            exponent++
        }
        while (mantissa < 0.75) {
            mantissa *= 2.0
            exponent--
        }
        val z = (mantissa - 1.0) / (mantissa + 1.0)
        val z2 = z * z
        var term = z
        var sum = 0.0
        var n = 1
        while (n <= 21) {
            sum += term / n
            term *= z2
            n += 2
        }
        return exponent * LN2 + 2.0 * sum
    }

    internal fun solitonCdf(k: Int): DoubleArray {
        require(k > 0)
        val cdf = DoubleArray(k)
        if (k == 1) {
            cdf[0] = 1.0
            return cdf
        }

        val r = max(1.0, SOLITON_C * deterministicLog(k / SOLITON_DELTA) * sqrt(k.toDouble()))
        val spike = min(k, ceil(k / r).toInt())
        var total = 0.0
        for (degree in 1..k) {
            val rho = if (degree == 1) 1.0 / k else 1.0 / (degree * (degree - 1).toDouble())
            val tau = when {
                degree < spike -> r / (degree * k)
                degree == spike -> (r * max(0.0, deterministicLog(r / SOLITON_DELTA))) / k
                else -> 0.0
            }
            total += rho + tau
            cdf[degree - 1] = total
        }
        for (index in cdf.indices) cdf[index] /= total
        cdf[k - 1] = 1.0
        return cdf
    }

    internal fun frameSeed(sessionId: Int, sequence: UInt): Int {
        var hash = ((sessionId + 1) * 0x9E3779B1u.toInt()) xor
            (sequence.toInt() + 0x85EBCA6Bu.toInt())
        hash = (hash xor (hash ushr 13)) * 0xC2B2AE35u.toInt()
        return hash xor (hash ushr 16)
    }

    fun frameIndices(k: Int, sessionId: Int, sequence: UInt): IntArray {
        require(k > 0)
        return frameIndices(k, solitonCdf(k), sessionId, sequence)
    }

    internal fun frameIndices(
        k: Int,
        cdf: DoubleArray,
        sessionId: Int,
        sequence: UInt,
    ): IntArray {
        val random = SplitMix32(frameSeed(sessionId, sequence))
        val u = random.nextUInt().toDouble() * 2.3283064365386963e-10 // 2^-32

        var low = 0
        var high = k - 1
        while (low < high) {
            val middle = (low + high) shr 1
            if (cdf[middle] >= u) high = middle else low = middle + 1
        }
        val degree = min(k, low + 1)

        if (degree > (k shr 3)) {
            val scratch = IntArray(k) { it }
            return IntArray(degree) { index ->
                val offset = (random.nextUInt() % (k - index).toUInt()).toInt()
                val selected = index + offset
                val temp = scratch[index]
                scratch[index] = scratch[selected]
                scratch[selected] = temp
                scratch[index]
            }
        }

        val selected = LinkedHashSet<Int>(degree)
        while (selected.size < degree) {
            selected += (random.nextUInt() % k.toUInt()).toInt()
        }
        return selected.toIntArray()
    }
}

class LTEncoder(
    payload: ByteArray,
    val blockLength: Int,
    val sessionId: Int,
) {
    val blockCount: Int
    private val blocks: Array<ByteArray>
    private val cdf: DoubleArray

    init {
        require(payload.isNotEmpty())
        require(blockLength > 0)
        require(sessionId in 0..0xFFFF)
        blockCount = max(1, ceil(payload.size.toDouble() / blockLength).toInt())
        blocks = Array(blockCount) { blockIndex ->
            ByteArray(blockLength).also { block ->
                val sourceStart = blockIndex * blockLength
                val length = min(blockLength, payload.size - sourceStart)
                if (length > 0) payload.copyInto(block, 0, sourceStart, sourceStart + length)
            }
        }
        cdf = FountainMath.solitonCdf(blockCount)
    }

    fun encode(sequence: UInt): ByteArray {
        val output = ByteArray(blockLength)
        val indices = FountainMath.frameIndices(blockCount, cdf, sessionId, sequence)
        for (blockIndex in indices) xorInto(output, blocks[blockIndex])
        return output
    }
}

class LTDecoder(
    val blockCount: Int,
    val blockLength: Int,
    val sessionId: Int,
    val totalLength: Int,
) {
    private class PendingFrame(
        val indices: LinkedHashSet<Int>,
        val bytes: ByteArray,
    )

    private val cdf: DoubleArray
    private val solved: Array<ByteArray?>
    private val byBlock: Array<MutableSet<PendingFrame>?>
    private val seen = HashSet<UInt>()

    var solvedCount: Int = 0
        private set
    var framesNew: Int = 0
        private set
    var framesDuplicate: Int = 0
        private set

    val isComplete: Boolean get() = solvedCount >= blockCount

    init {
        require(blockCount > 0)
        require(blockLength > 0)
        require(sessionId in 0..0xFFFF)
        require(totalLength > 0)
        require(blockCount == max(1, ceil(totalLength.toDouble() / blockLength).toInt()))
        cdf = FountainMath.solitonCdf(blockCount)
        solved = arrayOfNulls(blockCount)
        byBlock = arrayOfNulls(blockCount)
    }

    fun addFrame(sequence: UInt, block: ByteArray) {
        require(block.size == blockLength)
        if (!seen.add(sequence)) {
            framesDuplicate++
            return
        }
        framesNew++
        if (isComplete) return

        val indices = LinkedHashSet<Int>()
        indices.addAll(FountainMath.frameIndices(blockCount, cdf, sessionId, sequence).asList())
        val bytes = block.copyOf()

        val iterator = indices.iterator()
        while (iterator.hasNext()) {
            val blockIndex = iterator.next()
            val known = solved[blockIndex]
            if (known != null) {
                xorInto(bytes, known)
                iterator.remove()
            }
        }

        when (indices.size) {
            0 -> return
            1 -> resolve(indices.first(), bytes)
            else -> {
                val pending = PendingFrame(indices, bytes)
                for (blockIndex in indices) {
                    val waiting = byBlock[blockIndex] ?: LinkedHashSet<PendingFrame>().also {
                        byBlock[blockIndex] = it
                    }
                    waiting += pending
                }
            }
        }
    }

    private fun resolve(initialBlock: Int, initialBytes: ByteArray) {
        val queue = ArrayDeque<Pair<Int, ByteArray>>()
        queue.addLast(initialBlock to initialBytes)

        while (queue.isNotEmpty()) {
            val (blockIndex, bytes) = queue.removeLast()
            if (solved[blockIndex] != null) continue
            solved[blockIndex] = bytes
            solvedCount++

            val waiting = byBlock[blockIndex] ?: continue
            byBlock[blockIndex] = null
            for (pending in waiting) {
                xorInto(pending.bytes, bytes)
                pending.indices.remove(blockIndex)
                if (pending.indices.size == 1) {
                    val remaining = pending.indices.first()
                    byBlock[remaining]?.remove(pending)
                    if (solved[remaining] == null) {
                        queue.addLast(remaining to pending.bytes)
                    }
                }
            }
        }
    }

    fun assemble(): ByteArray? {
        if (!isComplete) return null
        val output = ByteArray(totalLength)
        for (blockIndex in 0 until blockCount) {
            val destinationStart = blockIndex * blockLength
            val length = min(blockLength, totalLength - destinationStart)
            if (length > 0) {
                solved[blockIndex]!!.copyInto(
                    destination = output,
                    destinationOffset = destinationStart,
                    startIndex = 0,
                    endIndex = length,
                )
            }
        }
        return output
    }
}

private fun xorInto(destination: ByteArray, source: ByteArray) {
    for (index in destination.indices) {
        destination[index] = (destination[index].toInt() xor source[index].toInt()).toByte()
    }
}
