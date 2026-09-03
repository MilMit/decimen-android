package io.decimen.android.sender

import android.graphics.Bitmap

enum class SenderMode {
    FILE,
    TEXT
}

enum class QrLayoutMode(val count: Int, val labelFa: String, val labelEn: String) {
    SINGLE(1, "تک کد (۱x)", "Single (1x)"),
    DUAL(2, "دوگانه (۲x)", "Dual (2x)"),
    QUAD(4, "چهارگانه (۴x)", "Quad (4x)")
}

data class SenderUiState(
    val mode: SenderMode = SenderMode.FILE,
    val payloadName: String = "",
    val mimeType: String = "application/octet-stream",
    val originalSize: Int = 0,
    val transmittedSize: Int = 0,
    val isCompressed: Boolean = false,
    val sha256Hex: String? = null,
    val blockLength: Int = 750,
    val targetFps: Int = 15,
    val layoutMode: QrLayoutMode = QrLayoutMode.SINGLE,
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val currentSequence: UInt = 0u,
    val currentBitmap: Bitmap? = null,
    val currentBitmaps: List<Bitmap> = emptyList(),
    val blockCount: Int = 0,
    val sessionId: Int = 0,
    val elapsedSeconds: Long = 0L,
    val textContent: String = "",
    val errorMessage: String? = null,
    val isExportingGif: Boolean = false,
    val exportedGifBytes: ByteArray? = null,
)
