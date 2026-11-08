package de.davscha.denkwerk

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.davscha.denkwerk.ui.App
import de.davscha.denkwerk.ui.AppViewModel
import de.davscha.denkwerk.ui.DenkwerkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Während eines Modell-Downloads bleibt der Bildschirm an, sonst bricht er ab
            val busy = viewModel.isDownloading
            LaunchedEffect(busy) {
                if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // Zurück aus den Android-Einstellungen? Dann Speicherort neu prüfen
            LifecycleResumeEffect(Unit) {
                viewModel.refreshStorage()
                onPauseOrDispose { }
            }
            DenkwerkTheme {
                Surface(color = MaterialTheme.colorScheme.background) { App(viewModel) }
            }
        }
    }
}
