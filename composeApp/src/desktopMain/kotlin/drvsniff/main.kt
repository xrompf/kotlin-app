package drvsniff

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val model = remember { AppModel(DesktopHost()) }
    Window(
        onCloseRequest = { model.stop(); exitApplication() },
        title = "Drvsniff",
        state = rememberWindowState(width = 480.dp, height = 860.dp),
    ) { App(model) }
}
