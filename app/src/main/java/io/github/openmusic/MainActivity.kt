package io.github.openmusic

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openmusic.platform.ScoreRenderer
import io.github.openmusic.ui.*

class MainActivity : ComponentActivity() {
    private val model: OpenMusicViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val render by model.render.collectAsStateWithLifecycle()
            var pendingExport by remember { mutableStateOf<ExportRequest?>(null) }
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importScore) }
            val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                val request = pendingExport
                if (uri != null && request != null) model.exportTo(uri, request)
                pendingExport = null
            }
            LaunchedEffect(Unit) { model.exportRequests.collect { request -> pendingExport = request; exporter.launch(request.name) } }
            DisposableEffect(state.running) {
                if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
            OpenMusicApp(state = state, onAction = { action ->
                if (action == UiAction.ImportScore) importer.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain", "*/*"))
                else model.onAction(action)
            }, scorePanel = { modifier ->
                ScoreRenderer(render.musicXml, state.notation == NotationUi.NUMBERED, render.positionQn, modifier, model::rendererError)
            })
        }
    }
    override fun onStop() { model.onBackground(); super.onStop() }
}
