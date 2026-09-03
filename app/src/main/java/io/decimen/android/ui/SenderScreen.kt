package io.decimen.android.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.decimen.android.receiver.toReadableBytes
import io.decimen.android.sender.QrLayoutMode
import io.decimen.android.sender.SenderMode
import io.decimen.android.sender.SenderUiState
import io.decimen.android.sender.SenderViewModel
import java.io.File

@Composable
fun SenderScreen(
    viewModel: SenderViewModel,
    isEnglish: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    // Keep screen on during active streaming
    val activity = context as? Activity
    DisposableEffect(state.isStreaming && !state.isPaused) {
        if (state.isStreaming && !state.isPaused) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.onFileSelected(context, it) }
    }

    val layoutDirection = if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mode Selection Tab (File / Text)
            TabRow(
                selectedTabIndex = if (state.mode == SenderMode.FILE) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Tab(
                    selected = state.mode == SenderMode.FILE,
                    onClick = { viewModel.setMode(SenderMode.FILE) },
                    text = { Text(if (isEnglish) "📁 File" else "📁 فایل") },
                )
                Tab(
                    selected = state.mode == SenderMode.TEXT,
                    onClick = { viewModel.setMode(SenderMode.TEXT) },
                    text = { Text(if (isEnglish) "📝 Text" else "📝 متن") },
                )
            }

            // Input Selection Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.mode == SenderMode.FILE) {
                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (state.payloadName.isEmpty())
                                    (if (isEnglish) "Choose File from Storage" else "انتخاب فایل از حافظه")
                                else
                                    (if (isEnglish) "Change File" else "تغییر فایل انتخابی")
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = state.textContent,
                            onValueChange = { viewModel.setTextContent(it) },
                            label = { Text(if (isEnglish) "Enter text or snippet..." else "متن یا پیام را اینجا وارد کنید...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                        )
                    }

                    // Metadata preview (DCF2)
                    if (state.payloadName.isNotEmpty()) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${if (isEnglish) "Name" else "نام"}: ${state.payloadName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.isCompressed) {
                                SuggestionChip(
                                    onClick = {},
                                    label = { Text("GZIP", fontSize = 11.sp) },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${if (isEnglish) "Size" else "حجم"}: ${state.originalSize.toLong().toReadableBytes()}" +
                                        (if (state.isCompressed) " → ${state.transmittedSize.toLong().toReadableBytes()}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = state.mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        state.sha256Hex?.let { sha ->
                            Text(
                                text = "SHA-256: ${sha.take(16)}...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    state.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // QR Code Stream Display Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val bitmaps = state.currentBitmaps
                    if (bitmaps.isNotEmpty()) {
                        when (state.layoutMode) {
                            QrLayoutMode.SINGLE -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(8.dp)
                                        .clickable { isFullscreen = true },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Image(
                                        bitmap = bitmaps[0].asImageBitmap(),
                                        contentDescription = "QR Frame",
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            QrLayoutMode.DUAL -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isFullscreen = true },
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (bm in bitmaps) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White)
                                                .padding(4.dp)
                                        ) {
                                            Image(bitmap = bm.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                                        }
                                    }
                                }
                            }
                            QrLayoutMode.QUAD -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isFullscreen = true },
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (bm in bitmaps.take(2)) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White)
                                                    .padding(4.dp)
                                            ) {
                                                Image(bitmap = bm.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        for (bm in bitmaps.drop(2).take(2)) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.White)
                                                    .padding(4.dp)
                                            ) {
                                                Image(bitmap = bm.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = if (isEnglish) "Tap QR for fullscreen" else "برای حالت تمام‌صفحه روی کد ضربه بزنید",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (state.payloadName.isEmpty())
                                    (if (isEnglish) "Select file or text first" else "ابتدا فایل یا متن را مشخص کنید")
                                else
                                    (if (isEnglish) "Press Start to stream" else "دکمه «شروع ارسال» را بزنید"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }

                    // Playback Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!state.isStreaming) {
                            Button(
                                onClick = { viewModel.startStreaming() },
                                modifier = Modifier.weight(1f),
                                enabled = state.payloadName.isNotEmpty(),
                            ) {
                                Text(if (isEnglish) "Start Streaming" else "شروع ارسال")
                            }
                        } else {
                            if (state.isPaused) {
                                Button(
                                    onClick = { viewModel.resumeStreaming() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (isEnglish) "Resume" else "ادامه")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.pauseStreaming() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (isEnglish) "Pause" else "توقف موقت")
                                }
                            }
                            Button(
                                onClick = { viewModel.stopStreaming() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text(if (isEnglish) "Stop" else "پایان")
                            }
                        }
                    }
                }
            }

            // Stream Settings & Multi-code Layout Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = if (isEnglish) "Stream Settings" else "تنظیمات ارسال",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // Multi-code Layout Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isEnglish) "Grid Layout (Multiple QRs)" else "چیدمان همزمان کدهای QR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QrLayoutMode.values().forEach { mode ->
                                FilterChip(
                                    selected = state.layoutMode == mode,
                                    onClick = { viewModel.setLayoutMode(mode) },
                                    label = { Text(if (isEnglish) mode.labelEn else mode.labelFa, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // FPS Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = if (isEnglish) "Target FPS" else "سرعت نمایش (FPS)")
                            Text(
                                text = "${state.targetFps} fps",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = state.targetFps.toFloat(),
                            onValueChange = { viewModel.setTargetFps(it.toInt()) },
                            valueRange = 5f..30f,
                            steps = 24,
                        )
                    }

                    // Block Size Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isEnglish) "Block Size (QR Density)" else "حجم هر بلوک (تراکم QR)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val options = listOf(500, 750, 1000, 1465)
                            options.forEach { size ->
                                FilterChip(
                                    selected = state.blockLength == size,
                                    onClick = { viewModel.setBlockLength(size) },
                                    label = { Text("$size B", fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // Export Looping Animation Button
                    Divider()
                    OutlinedButton(
                        onClick = {
                            viewModel.exportLoopingAnimation(context) { path ->
                                val file = File(path)
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/gif"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, if (isEnglish) "Share Animation" else "اشتراک انیمیشن"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.payloadName.isNotEmpty() && !state.isExportingGif,
                    ) {
                        if (state.isExportingGif) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isEnglish) "Exporting GIF..." else "در حال ساخت انیمیشن...")
                        } else {
                            Text(if (isEnglish) "🎞️ Export Looping GIF" else "🎞️ استخراج انیمیشن لوپ (GIF)")
                        }
                    }
                }
            }

            // Realtime Stats Card
            if (state.isStreaming) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (isEnglish) "Realtime Stats" else "آمار زنده استریم",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = if (isEnglish) "Sequence:" else "شماره فریم جاری:")
                            Text(text = "#${state.currentSequence}", fontWeight = FontWeight.Bold)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = if (isEnglish) "Blocks (K):" else "تعداد کل بلوک‌ها (K):")
                            Text(text = "${state.blockCount}")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = if (isEnglish) "Elapsed Time:" else "زمان سپری‌شده:")
                            Text(text = "${state.elapsedSeconds} ثانیه")
                        }
                    }
                }
            }
        }
    }

    // Fullscreen QR Dialog
    if (isFullscreen && state.currentBitmaps.isNotEmpty()) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { isFullscreen = false }
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .aspectRatio(1f)
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bm = state.currentBitmaps.firstOrNull()
                    if (bm != null) {
                        Image(
                            bitmap = bm.asImageBitmap(),
                            contentDescription = "Fullscreen QR",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
