package io.decimen.android.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import io.decimen.android.core.DecimenProtocol

/** Decodes only QR byte-mode segments that contain a valid Decimen frame. */
class QrFrameAnalyzer(
    private val onFrame: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.ALSO_INVERTED to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val luminance = image.toLuminanceArray()
            val source = PlanarYUVLuminanceSource(
                luminance,
                image.width,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            } catch (_: NotFoundException) {
                null
            } finally {
                reader.reset()
            }

            val bytes = result?.decimenPayloadBytes() ?: return
            if (DecimenProtocol.parseFrame(bytes) != null) onFrame(bytes)
        } catch (_: Throwable) {
            // A malformed camera frame must never stop the CameraX analyzer pipeline.
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toLuminanceArray(): ByteArray {
        val plane = planes[0]
        val buffer = plane.buffer
        val imageWidth = this.width
        val imageHeight = this.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val output = ByteArray(imageWidth * imageHeight)

        buffer.rewind()
        if (pixelStride == 1 && rowStride == imageWidth) {
            buffer.get(output, 0, minOf(output.size, buffer.remaining()))
            return output
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
        return output
    }

    @Suppress("UNCHECKED_CAST")
    private fun Result.decimenPayloadBytes(): ByteArray? {
        val segments = resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? Iterable<*>
        val byteSegments = segments?.mapNotNull { it as? ByteArray }.orEmpty()
        if (byteSegments.isNotEmpty()) {
            val totalSize = byteSegments.sumOf(ByteArray::size)
            return ByteArray(totalSize).also { output ->
                var offset = 0
                for (segment in byteSegments) {
                    segment.copyInto(output, offset)
                    offset += segment.size
                }
            }
        }

        // Fallback for unusual decoders. ZXing's rawBytes may contain QR codewords,
        // so it is accepted only when it already parses as a complete Decimen frame.
        return rawBytes?.takeIf { DecimenProtocol.parseFrame(it) != null }
    }
}
