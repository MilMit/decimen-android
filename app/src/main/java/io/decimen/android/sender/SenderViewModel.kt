package io.decimen.android.sender

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.decimen.android.core.Dcf2Container
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
            _uiState.update { it.copy(payloadName = "", originalSize = 0, transmittedSize = 0, blockCount = 0) }
        }
    }

    fun setLayoutMode(mode: QrLayoutMode) {
        _uiState.update { it.copy(layoutMode = mode) }
    }

    fun setTextContent(text: String) {
        _uiState.update { it.copy(textContent = text, errorMessage = null) }
        val rawBytes = text.toByteArray(StandardCharsets.UTF_8)
        if (rawBytes.isNotEmpty()) {
            try {
                val packed = Dcf2Container.packFile("snippet.txt", "text/plain;charset=utf-8", rawBytes)
                payloadBytes = packed.container
                val blockLength = _uiState.value.blockLength
                val blockCount = maxOf(1, kotlin.math.ceil(packed.container.size.toDouble() / blockLength).toInt())
                _uiState.update {
                    it.copy(
                        payloadName = "snippet.txt",
                        mimeType = "text/plain;charset=utf-8",
                        originalSize = packed.originalSize,
                        transmittedSize = packed.transmittedSize,
                        isCompressed = packed.compression == Dcf2Container.CompressionMode.GZIP,
                        sha256Hex = packed.sha256Hex,
                        blockCount = blockCount,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "خطا در آماده‌سازی متن: ${e.message}") }
            }
        } else {
            payloadBytes = null
            _uiState.update {
                it.copy(
                    payloadName = "",
                    originalSize = 0,
                    transmittedSize = 0,
                    isCompressed = false,
                    sha256Hex = null,
                    blockCount = 0,
                )
            }
        }
    }

    fun onFileSelected(context: Context, uri: Uri) {
        stopStreaming()
        viewModelScope.launch {
            try {
                val (name, mime, rawBytes) = withContext(Dispatchers.IO) {
                    var fileName = "file.bin"
                    var mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }

                    val stream = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("امکان باز کردن فایل وجود ندارد")
                    val fileBytes = stream.use { it.readBytes() }
                    Triple(fileName, mimeType, fileBytes)
                }

                if (rawBytes.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = "فایل انتخاب‌شده خالی است") }
                    return@launch
                }

                if (rawBytes.size > Dcf2Container.MAX_FILE_BYTES) {
                    _uiState.update { it.copy(errorMessage = "حجم فایل بیش از سقف مجاز (۶۴ مگابایت) است") }
                    return@launch
                }

                val packed = withContext(Dispatchers.Default) {
                    Dcf2Container.packFile(name, mime, rawBytes)
                }

                payloadBytes = packed.container
                val blockLength = _uiState.value.blockLength
                val blockCount = maxOf(1, kotlin.math.ceil(packed.container.size.toDouble() / blockLength).toInt())

                _uiState.update {
                    it.copy(
                        payloadName = name,
                        mimeType = mime,
                        originalSize = packed.originalSize,
                        transmittedSize = packed.transmittedSize,
                        isCompressed = packed.compression == Dcf2Container.CompressionMode.GZIP,
                        sha256Hex = packed.sha256Hex,
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
                val codeCount = _uiState.value.layoutMode.count

                val bitmaps = ArrayList<android.graphics.Bitmap>(codeCount)
                val baseSeq = sequence
                for (i in 0 until codeCount) {
                    val frameSeq = baseSeq + i.toUInt()
                    val encodedBlock = localEncoder.encode(frameSeq)
                    val header = DecimenProtocol.FrameHeader(
                        sessionId = sessionId,
                        sequence = frameSeq,
                        blockCount = localEncoder.blockCount,
                        blockLength = blockLength,
                        totalLength = totalLength,
                        payloadFnv = payloadFnv,
                        version = DecimenProtocol.WIRE_VERSION,
                    )
                    val frameBytes = DecimenProtocol.packFrame(header, encodedBlock)
                    val bitmap = QrCodeGenerator.generateQrBitmap(frameBytes, 512)
                    bitmaps.add(bitmap)
                }

                _uiState.update {
                    it.copy(
                        currentSequence = baseSeq,
                        currentBitmap = bitmaps.firstOrNull(),
                        currentBitmaps = bitmaps,
                    )
                }

                sequence += codeCount.toUInt()
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
                currentBitmaps = emptyList(),
                currentSequence = 0u,
            )
        }
    }

    fun exportLoopingAnimation(context: Context, onSaved: (String) -> Unit) {
        val bytes = payloadBytes
        if (bytes == null || bytes.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "داده‌ای برای استخراج انیمیشن وجود ندارد") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExportingGif = true) }
            try {
                val filePath = withContext(Dispatchers.Default) {
                    val blockLength = _uiState.value.blockLength
                    val sessionId = _uiState.value.sessionId.let { if (it == 0) Random.nextInt(0x10000) else it }
                    val localEncoder = LTEncoder(bytes, blockLength, sessionId)
                    val payloadFnv = DecimenProtocol.fnv1a(bytes)
                    val totalLength = bytes.size.toLong()
                    val frameCount = minOf(localEncoder.blockCount * 2, 60).coerceAtLeast(10)

                    val frames = ArrayList<android.graphics.Bitmap>(frameCount)
                    for (seq in 0 until frameCount) {
                        val encodedBlock = localEncoder.encode(seq.toUInt())
                        val header = DecimenProtocol.FrameHeader(
                            sessionId = sessionId,
                            sequence = seq.toUInt(),
                            blockCount = localEncoder.blockCount,
                            blockLength = blockLength,
                            totalLength = totalLength,
                            payloadFnv = payloadFnv,
                        )
                        val frameBytes = DecimenProtocol.packFrame(header, encodedBlock)
                        frames.add(QrCodeGenerator.generateQrBitmap(frameBytes, 384))
                    }

                    val gifBytes = GifEncoder.createGif(frames, _uiState.value.targetFps)
                    val exportDir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                    val gifFile = java.io.File(exportDir, "decimen_loop_${System.currentTimeMillis()}.gif")
                    gifFile.writeBytes(gifBytes)
                    gifFile.absolutePath
                }

                _uiState.update { it.copy(isExportingGif = false) }
                onSaved(filePath)
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isExportingGif = false,
                        errorMessage = "خطا در صدور انیمیشن: ${e.localizedMessage}"
                    )
                }
            }
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
