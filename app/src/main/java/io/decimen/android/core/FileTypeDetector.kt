package io.decimen.android.core

data class DetectedFileType(
    val mimeType: String,
    val extension: String,
    val label: String,
)

object FileTypeDetector {
    fun detect(bytes: ByteArray): DetectedFileType {
        if (bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return DetectedFileType("image/png", "png", "PNG")
        }
        if (bytes.startsWith(0xFF, 0xD8, 0xFF)) {
            return DetectedFileType("image/jpeg", "jpg", "JPEG")
        }
        if (bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a")) {
            return DetectedFileType("image/gif", "gif", "GIF")
        }
        if (bytes.startsWithAscii("%PDF-")) {
            return DetectedFileType("application/pdf", "pdf", "PDF")
        }
        if (bytes.size >= 12 && bytes.sliceAscii(0, 4) == "RIFF" && bytes.sliceAscii(8, 12) == "WEBP") {
            return DetectedFileType("image/webp", "webp", "WebP")
        }
        if (bytes.size >= 12 && bytes.sliceAscii(4, 8) == "ftyp") {
            return DetectedFileType("video/mp4", "mp4", "MP4")
        }
        if (bytes.startsWith(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWith(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWith(0x50, 0x4B, 0x07, 0x08)
        ) {
            return DetectedFileType("application/zip", "zip", "ZIP/APK")
        }
        if (bytes.startsWith(0x1F, 0x8B)) {
            return DetectedFileType("application/gzip", "gz", "GZip")
        }
        if (bytes.startsWithAscii("ID3")) {
            return DetectedFileType("audio/mpeg", "mp3", "MP3")
        }
        return DetectedFileType("application/octet-stream", "bin", "Binary")
    }

    private fun ByteArray.startsWith(vararg unsignedBytes: Int): Boolean {
        if (size < unsignedBytes.size) return false
        return unsignedBytes.indices.all { index ->
            (this[index].toInt() and 0xFF) == unsignedBytes[index]
        }
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        size >= value.length && sliceAscii(0, value.length) == value

    private fun ByteArray.sliceAscii(start: Int, end: Int): String =
        copyOfRange(start, end).toString(Charsets.US_ASCII)
}
