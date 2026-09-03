package io.decimen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.decimen.android.receiver.ReceiverViewModel
import io.decimen.android.sender.SenderViewModel
import io.decimen.android.ui.DecimenTheme
import io.decimen.android.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DecimenTheme(darkTheme = true) {
                val receiverViewModel: ReceiverViewModel = viewModel()
                val senderViewModel: SenderViewModel = viewModel()
                MainScreen(receiverViewModel, senderViewModel)
            }
        }
    }
}
