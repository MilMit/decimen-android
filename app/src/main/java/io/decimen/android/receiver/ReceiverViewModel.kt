package io.decimen.android.receiver

import androidx.lifecycle.ViewModel
import io.decimen.android.core.Dcf2Container
import io.decimen.android.core.DecimenProtocol
import io.decimen.android.core.FileTypeDetector
import io.decimen.android.core.LTDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class ReceiverViewModel : ViewModel() {
    private val mutableState = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = mutableState.asStateFlow()

    private var decoder: LTDecoder? = null
    private var activeHeader: DecimenProtocol.FrameHeader? = null
    private var startedAtNanos: Long = 0L
    private var completedPayload: ByteArray? = null

    @Synchronized
    fun acceptFrame(frameBytes: ByteArray) {
        if (!mutableState.value.cameraActive) return
        val parsed = DecimenProtocol.parseFrame(frameBytes) ?: return
        val header = parsed.header

        val needsNewSession = activeHeader?.let { current ->
            current.sessionId != header.sessionId ||
                current.blockCount != header.blockCount ||
                current.blockLength != header.blockLength ||
                current.totalLength != header.totalLength ||
                current.payloadFnv != header.payloadFnv
        } ?: true

        if (needsNewSession) {
            startSession(header)
        }

        val currentDecoder = decoder ?: return
        currentDecoder.addFrame(header.sequence, parsed.block)
        val elapsed = ((System.nanoTime() - startedAtNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
        val progress = (currentDecoder.framesNew / (currentDecoder.blockCount * OVERHEAD_EST)).toFloat()
            .coerceIn(0f, 0.99f)
        val estimatedRate = currentDecoder.framesNew * currentDecoder.blockLength /
            OVERHEAD_EST / 1024.0 / elapsed

        mutableState.value = mutableState.value.copy(
            phase = ReceiverPhase.RECEIVING,
            status = "در حال دریافت جریان شماره ${header.sessionId}",
            progress = progress,
            framesNew = currentDecoder.framesNew,
            framesDuplicate = currentDecoder.framesDuplicate,
            solvedBlocks = currentDecoder.solvedCount,
            elapsedSeconds = elapsed,
            estimatedRateKbps = estimatedRate,
            saveMessage = null,
        )

        if (currentDecoder.isComplete) finish(header, currentDecoder, elapsed)
    }

    @Synchronized
    private fun startSession(header: DecimenProtocol.FrameHeader) {
        val totalLength = header.totalLength.toInt()
        decoder = LTDecoder(
            blockCount = header.blockCount,
            blockLength = header.blockLength,
            sessionId = header.sessionId,
            totalLength = totalLength,
        )
        activeHeader = header
        completedPayload = null
        startedAtNanos = System.nanoTime()
        mutableState.value = ReceiverUiState(
            phase = ReceiverPhase.RECEIVING,
            status = "جریان پیدا شد؛ دریافت شروع شد",
            sessionId = header.sessionId,
            blockCount = header.blockCount,
            blockLength = header.blockLength,
            totalBytes = header.totalLength,
            cameraActive = true,
        )
    }

    @Synchronized
    private fun finish(
        header: DecimenProtocol.FrameHeader,
        currentDecoder: LTDecoder,
        elapsed: Double,
    ) {
        val payload = currentDecoder.assemble() ?: return
        val hashVerified = DecimenProtocol.fnv1a(payload) == header.payloadFnv
        if (!hashVerified) {
            completedPayload = null
            mutableState.value = mutableState.value.copy(
                phase = ReceiverPhase.ERROR,
                status = "فایل بازسازی شد اما هش آن با فرستنده یکی نیست",
                progress = 1f,
                hashVerified = false,
                cameraActive = false,
            )
            return
        }

        completedPayload = payload

        if (Dcf2Container.isDcf2(payload)) {
            val opticalFile = Dcf2Container.unpackFile(payload)
            if (opticalFile != null) {
                completedPayload = opticalFile.bytes
                val previewBmp = if (opticalFile.type.startsWith("image/")) {
                    try {
                        android.graphics.BitmapFactory.decodeByteArray(opticalFile.bytes, 0, opticalFile.bytes.size)
                    } catch (_: Exception) { null }
                } else null

                val previewTxt = if (opticalFile.type.startsWith("text/") || opticalFile.name.endsWith(".txt") || opticalFile.name.endsWith(".json")) {
                    try {
                        String(opticalFile.bytes, java.nio.charset.StandardCharsets.UTF_8).take(3000)
                    } catch (_: Exception) { null }
                } else null

                mutableState.value = mutableState.value.copy(
                    phase = ReceiverPhase.COMPLETE,
                    status = "انتقال کامل و صحت SHA-256 تأیید شد",
                    progress = 1f,
                    elapsedSeconds = elapsed,
                    hashVerified = true,
                    sha256Verified = true,
                    sha256Hex = opticalFile.sha256Hex,
                    isCompressed = opticalFile.compression == Dcf2Container.CompressionMode.GZIP,
                    originalBytesSize = opticalFile.bytes.size.toLong(),
                    suggestedFileName = opticalFile.name,
                    mimeType = opticalFile.type,
                    detectedType = opticalFile.type,
                    cameraActive = false,
                    previewBitmap = previewBmp,
                    previewText = previewTxt,
                )
                return
            }
        }

        // Fallback for legacy v1 raw payload without DCF2 container
        val type = FileTypeDetector.detect(payload)
        val suggestedName = "decimen-${header.sessionId}.${type.extension}"
        val previewBmp = if (type.mimeType.startsWith("image/")) {
            try {
                android.graphics.BitmapFactory.decodeByteArray(payload, 0, payload.size)
            } catch (_: Exception) { null }
        } else null

        val previewTxt = if (type.mimeType.startsWith("text/")) {
            try {
                String(payload, java.nio.charset.StandardCharsets.UTF_8).take(3000)
            } catch (_: Exception) { null }
        } else null

        mutableState.value = mutableState.value.copy(
            phase = ReceiverPhase.COMPLETE,
            status = "انتقال کامل و صحت فایل تأیید شد",
            progress = 1f,
            elapsedSeconds = elapsed,
            hashVerified = true,
            sha256Verified = null,
            suggestedFileName = suggestedName,
            mimeType = type.mimeType,
            detectedType = type.label,
            originalBytesSize = payload.size.toLong(),
            cameraActive = false,
            previewBitmap = previewBmp,
            previewText = previewTxt,
        )
    }

    @Synchronized
    fun payloadForSaving(): ByteArray? = completedPayload

    fun onFileSaved(fileName: String) {
        mutableState.value = mutableState.value.copy(
            saveMessage = "فایل با موفقیت ذخیره شد: $fileName",
        )
    }

    fun onSaveFailed(message: String) {
        mutableState.value = mutableState.value.copy(
            saveMessage = "ذخیره فایل ناموفق بود: $message",
        )
    }

    fun onCameraError(message: String) {
        mutableState.value = mutableState.value.copy(
            phase = ReceiverPhase.ERROR,
            status = "خطای دوربین: $message",
            cameraActive = false,
        )
    }

    @Synchronized
    fun reset() {
        decoder = null
        activeHeader = null
        completedPayload = null
        startedAtNanos = 0L
        mutableState.value = ReceiverUiState()
    }

    companion object {
        private const val OVERHEAD_EST = 1.18
    }
}

fun Long.toReadableBytes(): String = when {
    this >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", this / 1024.0 / 1024.0)
    this >= 1024L -> String.format(Locale.US, "%.1f KB", this / 1024.0)
    else -> "$this B"
}
