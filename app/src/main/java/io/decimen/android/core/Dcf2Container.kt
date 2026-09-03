package io.decimen.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * DCF2 (Decimen Container Format 2) handles metadata encapsulation:
 * - Preserves original filename and MIME type
 * - SHA-256 integrity digest of original uncompressed content
 * - Optional GZip compression (if beneficial)
 * - File sizes up to 64 MB
 */
object Dcf2Container {
    val FILE_MAGIC = byteArrayOf(0x44, 0x43, 0x46, 0x32) // "DCF2"
    const val FILE_HEADER_LEN = 49
    const val MAX_FILE_BYTES: Long = 64L * 1024L * 1024L // 64 MB

    enum class CompressionMode { NONE, GZIP }

    data class PackedOpticalFile(
        val container: ByteArray,
        val compression: CompressionMode,
        val originalSize: Int,
        val transmittedSize: Int,
        val sha256Hex: String,
    )

    data class OpticalFile(
        val name: String,
        val type: String,
        val bytes: ByteArray,
        val sha256: ByteArray,
        val compression: CompressionMode,
        val transmittedSize: Int,
        val sha256Hex: String,
    )

    private val PRECOMPRESSED_EXTENSIONS = setOf(
        "gz", "tgz", "bz2", "xz", "7z", "rar", "zip", "apk", "jar",
        "jpg", "jpeg", "png", "webp", "gif",
        "mp3", "aac", "ogg", "flac", "m4a",
        "mp4", "mkv", "webm", "avi", "mov"
    )

    fun isPrecompressed(name: String, mimeType: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in PRECOMPRESSED_EXTENSIONS) return true
        val mime = mimeType.split(';')[0].trim().lowercase()
        if (mime.startsWith("video/") || mime.startsWith("image/") || mime.startsWith("audio/")) {
            if (mime == "image/svg+xml" || mime == "image/bmp" || mime == "audio/wav") return false
            return true
        }
        if (mime.endsWith("+zip") || mime.contains("compressed") || mime.contains("zip")) return true
        return false
    }

    fun safeFileName(rawName: String): String {
        val base = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        return if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") "transfer.bin" else cleaned
    }

    fun sha256(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return sha256(bytes).joinToString("") { "%02x".format(it) }
    }

    fun gzip(bytes: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(bytes.size)
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    fun gunzip(bytes: ByteArray, maxOutputBytes: Long): ByteArray {
        val bis = ByteArrayInputStream(bytes)
        val bos = ByteArrayOutputStream()
        GZIPInputStream(bis).use { gzis ->
            val buf = ByteArray(8192)
            var total = 0L
            var read: Int
            while (gzis.read(buf).also { read = it } != -1) {
                total += read
                if (total > maxOutputBytes) {
                    throw IllegalStateException("Decompressed size exceeds maximum safety limit of $maxOutputBytes bytes")
                }
                bos.write(buf, 0, read)
            }
        }
        return bos.toByteArray()
    }

    fun isDcf2(bytes: ByteArray): Boolean {
        if (bytes.size < FILE_HEADER_LEN) return false
        return bytes[0] == FILE_MAGIC[0] &&
                bytes[1] == FILE_MAGIC[1] &&
                bytes[2] == FILE_MAGIC[2] &&
                bytes[3] == FILE_MAGIC[3]
    }

    fun packFile(name: String, type: String, bytes: ByteArray): PackedOpticalFile {
        require(bytes.isNotEmpty()) { "File cannot be empty" }
        require(bytes.size <= MAX_FILE_BYTES) { "File exceeds max limit of 64 MB" }

        val safeName = safeFileName(name)
        val safeType = if (type.isBlank()) "application/octet-stream" else type.trim()
        val nameBytes = safeName.toByteArray(StandardCharsets.UTF_8)
        val typeBytes = safeType.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size <= 0xFFFF && typeBytes.size <= 0xFFFF) { "Metadata too large" }

        val sha256Bytes = sha256(bytes)
        val sha256Hex = sha256Bytes.joinToString("") { "%02x".format(it) }

        val tryGzip = bytes.size >= 768 && !isPrecompressed(safeName, safeType)
        val compressed = if (tryGzip) {
            try { gzip(bytes) } catch (_: Exception) { null }
        } else null

        val useGzip = compressed != null && (compressed.size + 64 < bytes.size)
        val transmitted = if (useGzip) compressed!! else bytes
        val compressionMode = if (useGzip) CompressionMode.GZIP else CompressionMode.NONE

        val totalContainerLen = FILE_HEADER_LEN + nameBytes.size + typeBytes.size + transmitted.size
        val container = ByteBuffer.allocate(totalContainerLen)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put(FILE_MAGIC) // 0..3
                put(if (useGzip) 1.toByte() else 0.toByte()) // 4: compression
                putShort(nameBytes.size.toShort()) // 5..6: name length
                putShort(typeBytes.size.toShort()) // 7..8: type length
                putInt(bytes.size) // 9..12: original file length
                putInt(transmitted.size) // 13..16: transmitted length
                put(sha256Bytes) // 17..48: 32 bytes SHA-256
                put(nameBytes) // 49..
                put(typeBytes)
                put(transmitted)
            }
            .array()

        return PackedOpticalFile(
            container = container,
            compression = compressionMode,
            originalSize = bytes.size,
            transmittedSize = transmitted.size,
            sha256Hex = sha256Hex,
        )
    }

    fun unpackFile(container: ByteArray): OpticalFile? {
        if (!isDcf2(container)) return null
        val buffer = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN)

        val compressionByte = buffer.get(4).toInt() and 0xFF
        val compression = if (compressionByte == 1) CompressionMode.GZIP else CompressionMode.NONE
        val nameLength = buffer.getShort(5).toInt() and 0xFFFF
        val typeLength = buffer.getShort(7).toInt() and 0xFFFF
        val originalLength = buffer.getInt(9).toLong() and 0xFFFF_FFFFL
        val transmittedLength = buffer.getInt(13).toLong() and 0xFFFF_FFFFL

        val dataOffset = FILE_HEADER_LEN + nameLength + typeLength
        if (dataOffset + transmittedLength != container.size.toLong()) return null
        if (originalLength > MAX_FILE_BYTES || transmittedLength > MAX_FILE_BYTES) return null

        val sha256Bytes = ByteArray(32)
        buffer.position(17)
        buffer.get(sha256Bytes)

        val nameBytes = ByteArray(nameLength)
        buffer.position(FILE_HEADER_LEN)
        buffer.get(nameBytes)
        val name = safeFileName(String(nameBytes, StandardCharsets.UTF_8))

        val typeBytes = ByteArray(typeLength)
        buffer.position(FILE_HEADER_LEN + nameLength)
        buffer.get(typeBytes)
        val type = String(typeBytes, StandardCharsets.UTF_8).ifBlank { "application/octet-stream" }

        val transmittedBytes = ByteArray(transmittedLength.toInt())
        buffer.position(dataOffset)
        buffer.get(transmittedBytes)

        val uncompressedBytes = if (compression == CompressionMode.GZIP) {
            try {
                gunzip(transmittedBytes, originalLength)
            } catch (e: Exception) {
                return null
            }
        } else {
            transmittedBytes
        }

        if (uncompressedBytes.size.toLong() != originalLength) return null

        val computedSha = sha256(uncompressedBytes)
        if (!computedSha.contentEquals(sha256Bytes)) {
            return null
        }

        return OpticalFile(
            name = name,
            type = type,
            bytes = uncompressedBytes,
            sha256 = sha256Bytes,
            compression = compression,
            transmittedSize = transmittedLength.toInt(),
            sha256Hex = sha256Bytes.joinToString("") { "%02x".format(it) }
        )
    }
}
