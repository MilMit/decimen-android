package io.decimen.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.decimen.android.receiver.ReceiverViewModel
import io.decimen.android.sender.SenderViewModel

enum class AppTab {
    RECEIVER,
    SENDER
}

@Composable
fun MainScreen(
    receiverViewModel: ReceiverViewModel,
    senderViewModel: SenderViewModel,
    modifier: Modifier = Modifier,
) {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.RECEIVER) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
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
                                "دریافت فایل",
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
                                "ارسال فایل",
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
                    AppTab.RECEIVER -> ReceiverScreen(receiverViewModel)
                    AppTab.SENDER -> SenderScreen(senderViewModel)
                }
            }
        }
    }
}
