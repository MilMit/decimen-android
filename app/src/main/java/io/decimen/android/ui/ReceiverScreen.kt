package io.decimen.android.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.decimen.android.camera.DecimenCameraPreview
import io.decimen.android.receiver.ReceiverPhase
import io.decimen.android.receiver.ReceiverUiState
import io.decimen.android.receiver.ReceiverViewModel
import io.decimen.android.receiver.toReadableBytes
import java.util.Locale

@Composable
fun ReceiverScreen(
    viewModel: ReceiverViewModel,
    isEnglish: Boolean = false,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    KeepScreenAwake(enabled = state.cameraActive && permissionGranted)

    val createDocumentContract = remember(state.mimeType) {
        ActivityResultContracts.CreateDocument(state.mimeType)
    }
    val saveLauncher = rememberLauncherForActivityResult(createDocumentContract) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val payload = viewModel.payloadForSaving()
        if (payload == null) {
            viewModel.onSaveFailed(if (isEnglish) "No payload to save" else "داده‌ای برای ذخیره وجود ندارد")
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(payload)
                output.flush()
            } ?: error(if (isEnglish) "System denied file writing" else "سیستم اجازه نوشتن فایل را نداد")
        }.onSuccess {
            viewModel.onFileSaved(uri.lastPathSegment ?: state.suggestedFileName.orEmpty())
        }.onFailure { error ->
            viewModel.onSaveFailed(error.message ?: (if (isEnglish) "Unknown error" else "خطای ناشناخته"))
        }
    }

    val layoutDirection = if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl

    androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                Header(isEnglish = isEnglish)
                CameraArea(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    permissionGranted = permissionGranted,
                    state = state,
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onFrame = viewModel::acceptFrame,
                    onCameraError = viewModel::onCameraError,
                )
                ReceiverPanel(
                    state = state,
                    isEnglish = isEnglish,
                    onSave = { state.suggestedFileName?.let(saveLauncher::launch) },
                    onReset = viewModel::reset,
                )
            }
        }
    }
}

@Composable
private fun Header(isEnglish: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "DECIMEN",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                text = if (isEnglish) "Optical File Receiver" else "انتقال نوری فایل — گیرنده اندروید",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
            )
        }
        Text(
            text = "v0.3.0",
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CameraArea(
    modifier: Modifier,
    permissionGranted: Boolean,
    state: ReceiverUiState,
    onRequestPermission: () -> Unit,
    onFrame: (ByteArray) -> Unit,
    onCameraError: (String) -> Unit,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 14.dp)
            .background(Color.Black, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !permissionGranted -> PermissionMessage(onRequestPermission)
            state.cameraActive -> {
                DecimenCameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onFrame = onFrame,
                    onCameraError = onCameraError,
                )
                ScannerCorners(Modifier.fillMaxSize())
                Text(
                    text = "QR را داخل کادر نگه دارید",
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 18.dp)
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    fontSize = 13.sp,
                )
            }
            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (state.phase == ReceiverPhase.COMPLETE) "✓" else "!",
                        color = if (state.phase == ReceiverPhase.COMPLETE) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (state.phase == ReceiverPhase.COMPLETE) {
                            "دریافت تمام شد"
                        } else {
                            "اسکن متوقف شد"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionMessage(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "برای خواندن QRهای متحرک، دسترسی دوربین لازم است.",
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestPermission) { Text("دادن دسترسی دوربین") }
    }
}

@Composable
private fun ScannerCorners(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.padding(42.dp)) {
        val side = minOf(size.width, size.height) * 0.76f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val corner = side * 0.18f
        val stroke = 5.dp.toPx()

        fun line(start: Offset, end: Offset) = drawLine(
            color = color,
            start = start,
            end = end,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        line(Offset(left, top + corner), Offset(left, top))
        line(Offset(left, top), Offset(left + corner, top))
        line(Offset(left + side - corner, top), Offset(left + side, top))
        line(Offset(left + side, top), Offset(left + side, top + corner))
    }
}

@Composable
private fun ReceiverPanel(
    state: ReceiverUiState,
    isEnglish: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var textCopied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.status,
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${(state.progress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                    )
                }
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )

                if (state.sessionId != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Metric(if (isEnglish) "New Frames" else "فریم جدید", state.framesNew.toString(), Modifier.weight(1f))
                        Metric(if (isEnglish) "Duplicates" else "تکراری", state.framesDuplicate.toString(), Modifier.weight(1f))
                        Metric(if (isEnglish) "Size" else "حجم", state.totalBytes.toReadableBytes(), Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Metric(
                            if (isEnglish) "Rate" else "سرعت تخمینی",
                            String.format(Locale.US, "%.1f KB/s", state.estimatedRateKbps),
                            Modifier.weight(1f),
                        )
                        Metric(
                            if (isEnglish) "Time" else "زمان",
                            String.format(Locale.US, "%.1f s", state.elapsedSeconds),
                            Modifier.weight(1f),
                        )
                        Metric(
                            if (isEnglish) "Solved" else "بلوک حل‌شده",
                            "${state.solvedBlocks}/${state.blockCount}",
                            Modifier.weight(1f),
                        )
                    }
                }

                if (state.phase == ReceiverPhase.COMPLETE) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    state.previewBitmap?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Preview",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    state.previewText?.let { txt ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isEnglish) "Text Content Preview:" else "پیش‌نمایش محتوای متن:",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(txt))
                                            textCopied = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(if (textCopied) (if (isEnglish) "Copied!" else "کپی شد") else (if (isEnglish) "Copy" else "کپی متن"), fontSize = 11.sp)
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = txt,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 6,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${if (isEnglish) "File" else "فایل"}: ${state.suggestedFileName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (state.sha256Verified == true) {
                            Text(
                                text = "SHA-256 ✓",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                        if (state.isCompressed) {
                            Text(
                                text = "GZIP",
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Button(
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isEnglish) "Save ${state.suggestedFileName}" else "ذخیره ${state.suggestedFileName}")
                    }
                }

                if (state.saveMessage != null) {
                    Text(
                        text = state.saveMessage,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 13.sp,
                    )
                }

                if (state.phase == ReceiverPhase.COMPLETE || state.phase == ReceiverPhase.ERROR) {
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (isEnglish) "Scan Next Transfer" else "اسکن انتقال جدید")
                    }
                }
            }
        }
        Text(
            text = "نسخه فعلی فقط گیرنده است و با پروتکل وب اصلی سازگار طراحی شده.",
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
            textAlign = TextAlign.Center,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = value, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun KeepScreenAwake(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = (context as? Activity)?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
