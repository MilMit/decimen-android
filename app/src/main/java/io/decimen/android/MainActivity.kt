package io.decimen.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.decimen.android.receiver.ReceiverViewModel
import io.decimen.android.ui.DecimenTheme
import io.decimen.android.ui.ReceiverScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DecimenTheme(darkTheme = true) {
                val receiverViewModel: ReceiverViewModel = viewModel()
                ReceiverScreen(receiverViewModel)
            }
        }
    }
}
