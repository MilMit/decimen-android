package io.decimen.android.sender

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.decimen.android.core.DecimenProtocol
import io.decimen.android.core.LTEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import kotlin.random.Random

class SenderViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(SenderUiState())
    val uiState: StateFlow<SenderUiState> = _uiState.asStateFlow()

    private var payloadBytes: ByteArray? = null
    private var encoder: LTEncoder? = null
    private var streamJob: Job? = null
    private var timerJob: Job? = null

    fun setMode(mode: SenderMode) {
        if (_uiState.value.mode == mode) return
        stopStreaming()
        _uiState.update {
            it.copy(
                mode = mode,
                errorMessage = null,
                currentBitmap = null,
                currentSequence = 0u,
            )
        }
        if (mode == SenderMode.TEXT && _uiState.value.textContent.isNotBlank()) {
            setTextContent(_uiState.value.textContent)
        } else if (mode == SenderMode.FILE) {
            payloadBytes = null
            _uiState.update { it.copy(payloadName = "", payloadSize = 0, blockCount = 0) }
        }
    }

    fun setTextContent(text: String) {
        _uiState.update { it.copy(textContent = text, errorMessage = null) }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isNotEmpty()) {
            payloadBytes = bytes
            val blockLength = _uiState.value.blockLength
            val blockCount = maxOf(1, kotlin.math.ceil(bytes.size.toDouble() / blockLength).toInt())
            _uiState.update {
                it.copy(
                    payloadName = "متن ارسالی (${bytes.size} بایت)",
                    payloadSize = bytes.size,
                    blockCount = blockCount,
                )
            }
        } else {
            payloadBytes = null
            _uiState.update {
                it.copy(
                    payloadName = "",
                    payloadSize = 0,
                    blockCount = 0,
                )
            }
        }
    }

    fun onFileSelected(context: Context, uri: Uri) {
        stopStreaming()
        viewModelScope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    var fileName = "file.bin"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }

                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("امکان باز کردن فایل وجود ندارد")
                    val fileBytes = stream.use { it.readBytes() }
                    fileName to fileBytes
                }

                if (bytes.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = "فایل انتخاب‌شده خالی است") }
                    return@launch
                }

                if (bytes.size > DecimenProtocol.MAX_PAYLOAD_LENGTH) {
                    _uiState.update { it.copy(errorMessage = "حجم فایل بیش از حد مجاز (حداکثر ۳۲ مگابایت) است") }
                    return@launch
                }

                payloadBytes = bytes
                val blockLength = _uiState.value.blockLength
                val blockCount = maxOf(1, kotlin.math.ceil(bytes.size.toDouble() / blockLength).toInt())

                _uiState.update {
                    it.copy(
                        payloadName = name,
                        payloadSize = bytes.size,
                        blockCount = blockCount,
                        errorMessage = null,
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(errorMessage = "خطا در خواندن فایل: ${e.localizedMessage}") }
            }
        }
    }

    fun setTargetFps(fps: Int) {
        val clamped = fps.coerceIn(5, 30)
        _uiState.update { it.copy(targetFps = clamped) }
    }

    fun setBlockLength(length: Int) {
        val clamped = length.coerceIn(250, DecimenProtocol.MAX_BLOCK_LENGTH)
        val wasStreaming = _uiState.value.isStreaming
        stopStreaming()
        val bytes = payloadBytes
        val blockCount = if (bytes != null && bytes.isNotEmpty()) {
            maxOf(1, kotlin.math.ceil(bytes.size.toDouble() / clamped).toInt())
        } else {
            0
        }
        _uiState.update { it.copy(blockLength = clamped, blockCount = blockCount) }
        if (wasStreaming && bytes != null && bytes.isNotEmpty()) {
            startStreaming()
        }
    }

    fun startStreaming() {
        val bytes = payloadBytes
        if (bytes == null || bytes.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "لطفاً ابتدا فایل یا متن را مشخص کنید") }
            return
        }

        stopStreaming()

        val blockLength = _uiState.value.blockLength
        val sessionId = Random.nextInt(0x10000)
        val payloadFnv = DecimenProtocol.fnv1a(bytes)
        val totalLength = bytes.size.toLong()
        val localEncoder = LTEncoder(bytes, blockLength, sessionId)
        encoder = localEncoder

        _uiState.update {
            it.copy(
                isStreaming = true,
                isPaused = false,
                sessionId = sessionId,
                blockCount = localEncoder.blockCount,
                currentSequence = 0u,
                elapsedSeconds = 0L,
                errorMessage = null,
            )
        }

        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                if (!_uiState.value.isPaused) {
                    _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
                }
            }
        }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var sequence = 0u
            while (isActive) {
                if (_uiState.value.isPaused) {
                    delay(50)
                    continue
                }

                val targetFps = _uiState.value.targetFps
                val intervalMs = (1000L / targetFps).coerceAtLeast(20L)
                val startTime = System.currentTimeMillis()

                val encodedBlock = localEncoder.encode(sequence)
                val header = DecimenProtocol.FrameHeader(
                    sessionId = sessionId,
                    sequence = sequence,
                    blockCount = localEncoder.blockCount,
                    blockLength = blockLength,
                    totalLength = totalLength,
                    payloadFnv = payloadFnv,
                )
                val frameBytes = DecimenProtocol.packFrame(header, encodedBlock)
                val bitmap = QrCodeGenerator.generateQrBitmap(frameBytes, 512)

                val currentSeq = sequence
                _uiState.update {
                    it.copy(
                        currentSequence = currentSeq,
                        currentBitmap = bitmap,
                    )
                }

                sequence++
                val elapsed = System.currentTimeMillis() - startTime
                val remainingDelay = (intervalMs - elapsed).coerceAtLeast(5L)
                delay(remainingDelay)
            }
        }
    }

    fun pauseStreaming() {
        _uiState.update { it.copy(isPaused = true) }
    }

    fun resumeStreaming() {
        _uiState.update { it.copy(isPaused = false) }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        timerJob?.cancel()
        timerJob = null
        encoder = null
        _uiState.update {
            it.copy(
                isStreaming = false,
                isPaused = false,
                currentBitmap = null,
                currentSequence = 0u,
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
    }
}
