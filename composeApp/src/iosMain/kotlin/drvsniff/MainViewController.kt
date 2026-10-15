package drvsniff

import androidx.compose.ui.window.ComposeUIViewController

private val model by lazy { AppModel(IosHost()) }

fun MainViewController() = ComposeUIViewController { App(model) }
