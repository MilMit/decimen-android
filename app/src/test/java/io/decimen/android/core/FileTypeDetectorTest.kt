package io.decimen.android.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeDetectorTest {
    @Test
    fun detectsCommonFormats() {
        assertEquals("png", FileTypeDetector.detect(byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )).extension)
        assertEquals("pdf", FileTypeDetector.detect("%PDF-1.7".toByteArray()).extension)
        assertEquals("zip", FileTypeDetector.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04)).extension)
        assertEquals("bin", FileTypeDetector.detect(byteArrayOf(1, 2, 3, 4)).extension)
    }
}
