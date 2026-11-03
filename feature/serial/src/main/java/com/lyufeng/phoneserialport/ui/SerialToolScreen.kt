package com.lyufeng.phoneserialport.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyufeng.phoneserialport.ui.theme.PhoneSerialPortTheme

@Composable
fun SerialToolScreen(onReturnHome: () -> Unit) {
    val model: SerialViewModel = viewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val manager = model.manager
        if (model.returnHome.value == ReturnHomeState.IDLE) manager.enterTool()
        fun resume() { manager.appVisible = true; manager.scan(true) }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> manager.appVisible = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { manager.appVisible = false; lifecycle.removeObserver(observer) }
    }
    PhoneSerialPortTheme { SerialApp(model, onReturnHome) }
}
