package io.decimen.android.sender

import android.graphics.Bitmap

enum class SenderMode {
    FILE,
    TEXT
}

data class SenderUiState(
    val mode: SenderMode = SenderMode.FILE,
    val payloadName: String = "",
    val payloadSize: Int = 0,
    val blockLength: Int = 750,
    val targetFps: Int = 15,
    val isStreaming: Boolean = false,
    val isPaused: Boolean = false,
    val currentSequence: UInt = 0u,
    val currentBitmap: Bitmap? = null,
    val blockCount: Int = 0,
    val sessionId: Int = 0,
    val elapsedSeconds: Long = 0L,
    val textContent: String = "",
    val errorMessage: String? = null,
)
