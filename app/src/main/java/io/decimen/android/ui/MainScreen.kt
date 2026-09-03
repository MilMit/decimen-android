package io.decimen.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.decimen.android.receiver.ReceiverViewModel
import io.decimen.android.sender.SenderViewModel

enum class AppTab {
    RECEIVER,
    SENDER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    receiverViewModel: ReceiverViewModel,
    senderViewModel: SenderViewModel,
    modifier: Modifier = Modifier,
) {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.RECEIVER) }
    var isEnglish by rememberSaveable { mutableStateOf(false) }
    var showShareDialog by rememberSaveable { mutableStateOf(false) }

    val layoutDirection = if (isEnglish) LayoutDirection.Ltr else LayoutDirection.Rtl

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isEnglish) "Decimen Optical" else "دِسیمن (Decimen)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        // Share Web Receiver link button
                        IconButton(onClick = { showShareDialog = true }) {
                            Text("🔗", fontSize = 18.sp)
                        }
                        // Language switcher button
                        TextButton(onClick = { isEnglish = !isEnglish }) {
                            Text(
                                text = if (isEnglish) "فارسی" else "EN",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    NavigationBarItem(
                        selected = currentTab == AppTab.RECEIVER,
                        onClick = { currentTab = AppTab.RECEIVER },
                        icon = { Text("📥", fontSize = 20.sp) },
                        label = {
                            Text(
                                if (isEnglish) "Receive" else "دریافت فایل",
                                fontWeight = if (currentTab == AppTab.RECEIVER) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.SENDER,
                        onClick = { currentTab = AppTab.SENDER },
                        icon = { Text("📤", fontSize = 20.sp) },
                        label = {
                            Text(
                                if (isEnglish) "Send" else "ارسال فایل",
                                fontWeight = if (currentTab == AppTab.SENDER) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                when (currentTab) {
                    AppTab.RECEIVER -> ReceiverScreen(receiverViewModel, isEnglish = isEnglish)
                    AppTab.SENDER -> SenderScreen(senderViewModel, isEnglish = isEnglish)
                }
            }
        }

        if (showShareDialog) {
            ShareReceiverDialog(
                isEnglish = isEnglish,
                onDismiss = { showShareDialog = false },
            )
        }
    }
}
