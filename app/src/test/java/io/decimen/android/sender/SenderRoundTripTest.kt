package io.decimen.android.sender

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import io.decimen.android.core.DecimenProtocol
import io.decimen.android.core.LTDecoder
import io.decimen.android.core.LTEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class SenderRoundTripTest {

    @Test
    fun senderFramePackingAndDecodingWithPacketLoss() {
        val payload = "سلام دنیا! این یک تست کامل برای انتقال فایل نوری با کدهای فواره‌ای در دیسیمن اندروید است.".toByteArray(StandardCharsets.UTF_8)
        val blockLength = 32
        val sessionId = 0xABCD
        val encoder = LTEncoder(payload, blockLength, sessionId)
        val decoder = LTDecoder(encoder.blockCount, blockLength, sessionId, payload.size)
        val payloadFnv = DecimenProtocol.fnv1a(payload)

        var sequence = 0u
        var dropped = 0
        while (!decoder.isComplete && sequence < 500u) {
            val encodedBlock = encoder.encode(sequence)
            val header = DecimenProtocol.FrameHeader(
                sessionId = sessionId,
                sequence = sequence,
                blockCount = encoder.blockCount,
                blockLength = blockLength,
                totalLength = payload.size.toLong(),
                payloadFnv = payloadFnv,
            )
            val frameBytes = DecimenProtocol.packFrame(header, encodedBlock)

            // Simulate 25% dropped frames
            if (sequence % 4u != 0u) {
                val parsed = DecimenProtocol.parseFrame(frameBytes)
                assertNotNull("Frame must be parseable", parsed)
                decoder.addFrame(parsed!!.header.sequence, parsed.block)
            } else {
                dropped++
            }
            sequence++
        }

        assertTrue("Decoder should successfully complete even with dropped frames", decoder.isComplete)
        assertTrue("Dropped frames should have occurred in test", dropped > 0)
        val assembled = decoder.assemble()
        assertNotNull("Assembled payload should not be null", assembled)
        assertArrayEquals("Assembled payload must match original byte-for-byte", payload, assembled)
        assertEquals("FNV1a hash must match", payloadFnv, DecimenProtocol.fnv1a(assembled!!))
    }

    @Test
    fun zxingByteSegmentRoundTrip() {
        val testBytes = byteArrayOf(0xD1.toByte(), 0x0C.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val content = String(testBytes, StandardCharsets.ISO_8859_1)

        val writer = com.google.zxing.qrcode.QRCodeWriter()
        val hints = mapOf(
            com.google.zxing.EncodeHintType.CHARACTER_SET to StandardCharsets.ISO_8859_1.name(),
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
        )
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, 200, 200, hints)

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }

        val source = RGBLuminanceSource(width, height, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.TRY_HARDER to true))
        }
        val result = reader.decode(bitmap)

        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as? Iterable<ByteArray>
        assertNotNull("BYTE_SEGMENTS metadata must be present in decoded QR", segments)
        val decodedBytes = segments!!.first()
        assertArrayEquals("Decoded bytes must match encoded bytes exactly", testBytes, decodedBytes)
    }
}
