package io.decimen.android.receiver

enum class ReceiverPhase {
    SEARCHING,
    RECEIVING,
    COMPLETE,
    ERROR,
}

data class ReceiverUiState(
    val phase: ReceiverPhase = ReceiverPhase.SEARCHING,
    val status: String = "دوربین را روبه‌روی QR متحرک بگیرید",
    val progress: Float = 0f,
    val sessionId: Int? = null,
    val blockCount: Int = 0,
    val blockLength: Int = 0,
    val totalBytes: Long = 0,
    val framesNew: Int = 0,
    val framesDuplicate: Int = 0,
    val solvedBlocks: Int = 0,
    val elapsedSeconds: Double = 0.0,
    val estimatedRateKbps: Double = 0.0,
    val hashVerified: Boolean? = null,
    val suggestedFileName: String? = null,
    val mimeType: String = "application/octet-stream",
    val detectedType: String? = null,
    val cameraActive: Boolean = true,
    val saveMessage: String? = null,
)
