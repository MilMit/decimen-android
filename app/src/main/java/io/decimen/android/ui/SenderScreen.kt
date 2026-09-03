package io.decimen.android.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.decimen.android.receiver.toReadableBytes
import io.decimen.android.sender.SenderMode
import io.decimen.android.sender.SenderUiState
import io.decimen.android.sender.SenderViewModel

@Composable
fun SenderScreen(
    viewModel: SenderViewModel,
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

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "فرستنده کدهای QR متحرک",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            // Mode Selector: File vs Text
            TabRow(
                selectedTabIndex = if (state.mode == SenderMode.FILE) 0 else 1,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
            ) {
                Tab(
                    selected = state.mode == SenderMode.FILE,
                    onClick = { viewModel.setMode(SenderMode.FILE) },
                    text = { Text("📁 ارسال فایل") },
                )
                Tab(
                    selected = state.mode == SenderMode.TEXT,
                    onClick = { viewModel.setMode(SenderMode.TEXT) },
                    text = { Text("📝 متن کوتاه") },
                )
            }

            // Payload Selection Card
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
                            Text(if (state.payloadName.isEmpty()) "انتخاب فایل از حافظه" else "تغییر فایل انتخابی")
                        }
                        if (state.payloadName.isNotEmpty()) {
                            Text(
                                text = "نام: ${state.payloadName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "حجم: ${state.payloadSize.toLong().toReadableBytes()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = state.textContent,
                            onValueChange = { viewModel.setTextContent(it) },
                            label = { Text("متن یا پیام را اینجا وارد کنید...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 5,
                        )
                        if (state.payloadSize > 0) {
                            Text(
                                text = "حجم متن: ${state.payloadSize.toLong().toReadableBytes()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // QR Code Stream Display
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
                    val bitmap = state.currentBitmap
                    if (bitmap != null) {
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
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "QR Frame",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = "برای حالت تمام‌صفحه روی QR ضربه بزنید",
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
                                text = if (state.payloadSize > 0) "برای شروع استریم، دکمه زیر را لمس کنید" else "فایل یا متن را انتخاب کنید",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                enabled = state.payloadSize > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("شروع استریم QR")
                            }
                        } else {
                            if (state.isPaused) {
                                Button(
                                    onClick = { viewModel.resumeStreaming() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("ادامه")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.pauseStreaming() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("توقف موقت")
                                }
                            }

                            Button(
                                onClick = { viewModel.stopStreaming() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("توقف انتقال")
                            }
                        }
                    }
                }
            }

            // Real-Time Stats Card
            if (state.isStreaming || state.blockCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "وضعیت استریم",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem("فریم ارسالی", "#${state.currentSequence}")
                            StatItem("بلوک‌های فواره (K)", "${state.blockCount}")
                            StatItem("زمان گذشته", "${state.elapsedSeconds} ثانیه")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatItem("Session ID", state.sessionId.toString(16).uppercase().padStart(4, '0'))
                            StatItem("نرخ فریم", "${state.targetFps} FPS")
                            StatItem("حجم بلوک", "${state.blockLength} بایت")
                        }
                    }
                }
            }

            // Tuning Settings Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "تنظیمات سرعت و تراکم",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // Target FPS Slider
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("سرعت پخش (FPS):", style = MaterialTheme.typography.bodyMedium)
                            Text("${state.targetFps} فریم/ثانیه", fontWeight = FontWeight.Bold)
                        }
                        Slider(
                            value = state.targetFps.toFloat(),
                            onValueChange = { viewModel.setTargetFps(it.toInt()) },
                            valueRange = 5f..30f,
                            steps = 24,
                        )
                    }

                    // Block Size Selection
                    Text("حجم داده در هر فریم QR (تراکم):", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(500 to "سبک (500B)", 750 to "عادی (750B)", 1000 to "فشرده (1KB)", 1465 to "حداکثر (1.4KB)").forEach { (size, label) ->
                            FilterChip(
                                selected = state.blockLength == size,
                                onClick = { viewModel.setBlockLength(size) },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Fullscreen QR Code Dialog
        if (isFullscreen && state.currentBitmap != null) {
            Dialog(
                onDismissRequest = { isFullscreen = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                        .clickable { isFullscreen = false }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = state.currentBitmap!!.asImageBitmap(),
                        contentDescription = "Fullscreen QR",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
