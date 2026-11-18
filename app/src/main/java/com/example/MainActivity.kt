package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.skyplus.finance.presentation.MainViewModel
import com.skyplus.finance.presentation.SkyPlusApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  private val mainViewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      SkyPlusApp(viewModel = mainViewModel)
    }
  }

  override fun onStart() {
    super.onStart()
    mainViewModel.onAppResumed()
  }

  override fun onStop() {
    super.onStop()
    mainViewModel.onAppBackgrounded()
  }
}
