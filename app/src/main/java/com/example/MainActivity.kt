package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.debug.DebugScreen
import com.example.debug.DebugViewModel
import com.example.storage.FileProjectRepository
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val repository = FileProjectRepository(filesDir)

    setContent {
      MyApplicationTheme {
        val debugViewModel: DebugViewModel =
          viewModel(factory = DebugViewModel.Factory(repository))
        DebugScreen(viewModel = debugViewModel)
      }
    }
  }
}
