package io.decimen.android.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import io.decimen.android.core.DecimenProtocol
import java.nio.charset.StandardCharsets

/**
 * High-performance QR frame analyzer for camera video stream.
 *
 * Features:
 * - Proper sensor orientation rotation (0, 90, 180, 270 degrees) so QR codes appear upright.
 * - Reusable buffer pooling to eliminate GC churn and dropped frames at 30+ FPS.
 * - HybridBinarizer (paper/real world) with fallback to GlobalHistogramBinarizer (for bright screens/monitors).
 * - Dual payload extraction: BYTE_SEGMENTS and ISO-8859-1 raw character fallback.
 */
class QrFrameAnalyzer(
    private val onFrame: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
                DecodeHintType.CHARACTER_SET to StandardCharsets.ISO_8859_1.name(),
            ),
        )
    }

    // Reusable byte buffers to avoid allocating 1MB arrays every 33ms
    private var rawYuvBuffer: ByteArray? = null
    private var rotatedYuvBuffer: ByteArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            val width = image.width
            val height = image.height
            val totalPixels = width * height
            val rotationDegrees = image.imageInfo.rotationDegrees

            // Ensure buffer capacity
            val rawLuminance = getOrCreateRawBuffer(totalPixels)
            image.extractLuminanceTo(rawLuminance)

            // Rotate luminance data according to sensor orientation
            val (luminanceData, targetWidth, targetHeight) = if (rotationDegrees == 0) {
                Triple(rawLuminance, width, height)
            } else {
                val rotated = getOrCreateRotatedBuffer(totalPixels)
                rotateLuminance(rawLuminance, rotated, width, height, rotationDegrees)
                val (rw, rh) = if (rotationDegrees == 90 || rotationDegrees == 270) {
                    Pair(height, width)
                } else {
                    Pair(width, height)
                }
                Triple(rotated, rw, rh)
            }

            val source = PlanarYUVLuminanceSource(
                luminanceData,
                targetWidth,
                targetHeight,
                0,
                0,
                targetWidth,
                targetHeight,
                false,
            )

            // Pass 1: HybridBinarizer (optimal for natural lighting / paper)
            var result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (_: NotFoundException) {
                null
            } finally {
                reader.reset()
            }

            // Pass 2: GlobalHistogramBinarizer (optimal for computer monitors / mobile screens)
            if (result == null) {
                result = try {
                    reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source)))
                } catch (_: NotFoundException) {
                    null
                } finally {
                    reader.reset()
                }
            }

            val bytes = result?.decimenPayloadBytes() ?: return
            if (DecimenProtocol.parseFrame(bytes) != null) {
                onFrame(bytes)
            }
        } catch (_: Throwable) {
            // A malformed camera frame must never stop the CameraX analyzer pipeline.
        } finally {
            image.close()
        }
    }

    private fun getOrCreateRawBuffer(size: Int): ByteArray {
        val current = rawYuvBuffer
        return if (current != null && current.size >= size) {
            current
        } else {
            ByteArray(size).also { rawYuvBuffer = it }
        }
    }

    private fun getOrCreateRotatedBuffer(size: Int): ByteArray {
        val current = rotatedYuvBuffer
        return if (current != null && current.size >= size) {
            current
        } else {
            ByteArray(size).also { rotatedYuvBuffer = it }
        }
    }

    private fun ImageProxy.extractLuminanceTo(output: ByteArray) {
        val plane = planes[0]
        val buffer = plane.buffer
        val imageWidth = this.width
        val imageHeight = this.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        buffer.rewind()
        if (pixelStride == 1 && rowStride == imageWidth) {
            buffer.get(output, 0, minOf(output.size, buffer.remaining()))
            return
        }

        val row = ByteArray(rowStride)
        var outputOffset = 0
        for (y in 0 until imageHeight) {
            val bytesToRead = minOf(rowStride, buffer.remaining())
            if (bytesToRead <= 0) break
            buffer.get(row, 0, bytesToRead)
            var rowOffset = 0
            repeat(imageWidth) {
                if (rowOffset < bytesToRead) output[outputOffset++] = row[rowOffset]
                rowOffset += pixelStride
            }
        }
    }

    private fun rotateLuminance(
        src: ByteArray,
        dst: ByteArray,
        width: Int,
        height: Int,
        degrees: Int,
    ) {
        when (degrees) {
            90 -> {
                // Clockwise 90 deg: newWidth = height, newHeight = width
                var idx = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        dst[idx++] = src[y * width + x]
                    }
                }
            }
            180 -> {
                val total = width * height
                for (i in 0 until total) {
                    dst[i] = src[total - 1 - i]
                }
            }
            270 -> {
                // Clockwise 270 deg (Counter-clockwise 90 deg): newWidth = height, newHeight = width
                var idx = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        dst[idx++] = src[y * width + x]
                    }
                }
            }
            else -> {
                System.arraycopy(src, 0, dst, 0, width * height)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Result.decimenPayloadBytes(): ByteArray? {
        // Strategy 1: Check standard ZXing BYTE_SEGMENTS
        val segments = resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*>
        val byteSegments = segments?.mapNotNull { it as? ByteArray }.orEmpty()
        if (byteSegments.isNotEmpty()) {
            val totalSize = byteSegments.sumOf(ByteArray::size)
            val output = ByteArray(totalSize)
            var offset = 0
            for (segment in byteSegments) {
                segment.copyInto(output, offset)
                offset += segment.size
            }
            if (DecimenProtocol.parseFrame(output) != null) {
                return output
            }
        }

        // Strategy 2: Check result text converted as ISO-8859-1 bytes (fallback for single-stream decoders)
        val textBytes = runCatching { text?.toByteArray(StandardCharsets.ISO_8859_1) }.getOrNull()
        if (textBytes != null && DecimenProtocol.parseFrame(textBytes) != null) {
            return textBytes
        }

        // Strategy 3: Check rawBytes if already a valid Decimen frame
        rawBytes?.let { rb ->
            if (DecimenProtocol.parseFrame(rb) != null) return rb
        }

        return null
    }
}
