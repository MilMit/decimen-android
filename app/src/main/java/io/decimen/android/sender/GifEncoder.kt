package io.decimen.android.sender

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Lightweight Animated GIF89a encoder optimized specifically for 2-color (B/W) QR code frames.
 * Extremely fast and produces tiny looping GIF files.
 */
object GifEncoder {

    fun createGif(bitmaps: List<Bitmap>, fps: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        encode(bitmaps, fps, bos)
        return bos.toByteArray()
    }

    fun encode(bitmaps: List<Bitmap>, fps: Int, out: OutputStream) {
        if (bitmaps.isEmpty()) return
        val first = bitmaps.first()
        val width = first.width
        val height = first.height
        val delayHundredths = maxOf(2, (100 / maxOf(1, fps)))

        // 1. Header & Logical Screen Descriptor
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        // GCT Flag: Global Color Table present, 1 bit per pixel (2 colors), 2 entries
        // 0x80 | (0 << 4) | (0 << 3) | 0 = 0x80
        out.write(0x80)
        out.write(0) // Background color index (0: black or white)
        out.write(0) // Pixel aspect ratio

        // Global Color Table:
        // Index 0: Black (0, 0, 0)
        out.write(0); out.write(0); out.write(0)
        // Index 1: White (255, 255, 255)
        out.write(255); out.write(255); out.write(255)

        // 2. Netscape 2.0 Loop Extension
        out.write(0x21) // Extension Introducer
        out.write(0xFF) // Application Extension
        out.write(11)   // Block size
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)    // Sub-block size
        out.write(1)    // Loop sub-block ID
        writeShort(out, 0) // Loop count 0 = loop forever
        out.write(0)    // Block terminator

        // 3. Write each frame
        val pixels = IntArray(width * height)
        val colorIndices = ByteArray(width * height)

        for (bm in bitmaps) {
            bm.getPixels(pixels, 0, width, 0, 0, width, height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Index 1 if light/white, 0 if dark/black
                colorIndices[i] = if ((r + g + b) / 3 > 128) 1.toByte() else 0.toByte()
            }

            // Graphic Control Extension (Frame timing)
            out.write(0x21) // Extension Introducer
            out.write(0xF9) // Graphic Control Label
            out.write(4)    // Block size
            out.write(0x00) // Disposal: no disposal specified, no transparency
            writeShort(out, delayHundredths)
            out.write(0)    // Transparent color index (unused)
            out.write(0)    // Block terminator

            // Image Descriptor
            out.write(0x2C) // Image Separator
            writeShort(out, 0) // Left
            writeShort(out, 0) // Top
            writeShort(out, width)
            writeShort(out, height)
            out.write(0) // No Local Color Table, not interlaced

            // Image Data (LZW compressed)
            writeLzw(out, colorIndices, 2)
        }

        // GIF Trailer
        out.write(0x3B)
        out.flush()
    }

    private fun writeShort(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /** Simple LZW encoder for 2-color GIF (minCodeSize = 2). */
    private fun writeLzw(out: OutputStream, pixels: ByteArray, minCodeSize: Int) {
        out.write(minCodeSize)
        val clearCode = 1 shl minCodeSize
        val endOfInfo = clearCode + 1
        var nextCode = endOfInfo + 1
        var codeSize = minCodeSize + 1

        val prefixMap = HashMap<Long, Int>()
        fun resetDict() {
            prefixMap.clear()
            nextCode = endOfInfo + 1
            codeSize = minCodeSize + 1
        }

        // Bit accumulator
        var curBits = 0
        var curVal = 0
        val packet = ByteArray(255)
        var packetLen = 0

        fun flushPacket() {
            if (packetLen > 0) {
                out.write(packetLen)
                out.write(packet, 0, packetLen)
                packetLen = 0
            }
        }

        fun writeBits(code: Int) {
            curVal = curVal or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                packet[packetLen++] = (curVal and 0xFF).toByte()
                if (packetLen == 255) flushPacket()
                curVal = curVal ushr 8
                curBits -= 8
            }
        }

        writeBits(clearCode)

        if (pixels.isNotEmpty()) {
            var prefix = pixels[0].toLong() and 0xFF

            for (i in 1 until pixels.size) {
                val k = pixels[i].toLong() and 0xFF
                val key = (prefix shl 8) or k
                val existing = prefixMap[key]
                if (existing != null) {
                    prefix = existing.toLong()
                } else {
                    writeBits(prefix.toInt())
                    if (nextCode < 4096) {
                        prefixMap[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
                    } else {
                        writeBits(clearCode)
                        resetDict()
                    }
                    prefix = k
                }
            }
            writeBits(prefix.toInt())
        }

        writeBits(endOfInfo)

        // Flush remaining bits
        if (curBits > 0) {
            packet[packetLen++] = (curVal and 0xFF).toByte()
            if (packetLen == 255) flushPacket()
        }
        flushPacket()

        // Block terminator
        out.write(0)
    }
}
