package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination { TALK, AI, AGENT }
private enum class AiPage { HOME, MODEL, GENERATION }

/** Android presentation layer. Native inference remains owned by native-lib.cpp. */
class MainActivity : ComponentActivity() {
  companion object { init { System.loadLibrary("localllm_native") } }

  private external fun nativeLoadModel(modelPath: String): String
  private external fun nativeUnloadModel()
  private external fun nativeIsModelLoaded(): Boolean
  private external fun nativeGenerateWithSampling(
    prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
    typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long
  ): String

  private var modelStatus by mutableStateOf("No model loaded")
  private var selectedModelName by mutableStateOf("No GGUF model selected")
  private var output by mutableStateOf("Select and load a GGUF model to begin.")
  private var loading by mutableStateOf(false)

  private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) lifecycleScope.launch {
      loading = true; output = ""
      modelStatus = copyAndLoadModel(uri)
      loading = false
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState); enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        PlayerApp(
          modelStatus = modelStatus, modelName = selectedModelName, output = output, loading = loading,
          onPickModel = { modelPicker.launch(arrayOf("application/octet-stream", "application/*")) },
          onUnload = { nativeUnloadModel(); modelStatus = "No model loaded"; output = "Model unloaded." },
          onGenerate = { settings ->
            loading = true
            lifecycleScope.launch {
              output = withContext(Dispatchers.Default) {
                if (!nativeIsModelLoaded()) "ERROR: Load a GGUF model in AI > Model first."
                else nativeGenerateWithSampling(settings.prompt, settings.temperature, settings.topK, settings.topP,
                  settings.minP, settings.typicalP, settings.repetitionPenalty, settings.penaltyLastN, settings.seed)
              }
              loading = false
            }
          }
        )
      }
    }
  }

  private suspend fun copyAndLoadModel(uri: Uri): String = withContext(Dispatchers.IO) {
    try {
      val directory = File(filesDir, "models").apply { mkdirs() }
      val temp = File(directory, "model.gguf.partial")
      val final = File(directory, "model.gguf")
      contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
        ?: return@withContext "ERROR: Could not open the model file."
      if (!temp.renameTo(final) || final.length() <= 0) { temp.delete(); return@withContext "ERROR: Could not save the model file." }
      selectedModelName = uri.lastPathSegment ?: "Selected GGUF model"
      nativeLoadModel(final.absolutePath)
    } catch (error: Throwable) { "ERROR: ${error.message ?: error.javaClass.simpleName}" }
  }

  override fun onDestroy() { runCatching { nativeUnloadModel() }; super.onDestroy() }
}

private data class SamplingSettings(
  val prompt: String, val temperature: Float, val topK: Int, val topP: Float, val minP: Float,
  val typicalP: Float, val repetitionPenalty: Float, val penaltyLastN: Int, val seed: Long
)

@Composable
private fun PlayerApp(modelStatus: String, modelName: String, output: String, loading: Boolean,
  onPickModel: () -> Unit, onUnload: () -> Unit, onGenerate: (SamplingSettings) -> Unit) {
  var destination by rememberSaveable { mutableStateOf(Destination.TALK) }
  var aiPage by rememberSaveable { mutableStateOf(AiPage.HOME) }
  Scaffold(
    bottomBar = { NavigationBar {
      NavItem(Destination.TALK, destination, "Talk", Icons.Filled.Forum) { destination = Destination.TALK }
      NavItem(Destination.AI, destination, "AI", Icons.Filled.Memory) { destination = Destination.AI; aiPage = AiPage.HOME }
      NavItem(Destination.AGENT, destination, "Agent", Icons.Filled.SmartToy) { destination = Destination.AGENT }
    } }
  ) { padding ->
    Surface(Modifier.fillMaxSize().padding(padding)) {
      when (destination) {
        Destination.TALK -> UnavailableScreen("Talk", "Character chat will appear here when the native streaming conversation API is available.", "Talk intentionally has no Thinking or long-term Memory.")
        Destination.AGENT -> UnavailableScreen("Agent", "Agent runs, tools, and memory require a native harness and are not available in this build.", "No tool controls are exposed until they can execute safely.")
        Destination.AI -> when (aiPage) {
          AiPage.HOME -> AiHome(modelName, modelStatus, loading, { aiPage = AiPage.MODEL }, { aiPage = AiPage.GENERATION })
          AiPage.MODEL -> ModelScreen(modelName, modelStatus, loading, onPickModel, onUnload, { aiPage = AiPage.HOME })
          AiPage.GENERATION -> GenerationScreen(output, loading, onGenerate, { aiPage = AiPage.HOME })
        }
      }
    }
  }
}

@Composable private fun NavItem(target: Destination, selected: Destination, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) =
  NavigationBarItem(selected = target == selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })

@Composable
private fun AiHome(modelName: String, modelStatus: String, loading: Boolean, openModel: () -> Unit, openGeneration: () -> Unit) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Configure the local LLM engine. Settings shown here are passed to the native sampler.")
    SettingCard("Model", if (modelStatus.startsWith("SUCCESS:")) "$modelName · Loaded" else modelStatus, openModel)
    SettingCard("Generation", "Temperature, Top-K, Top-P, Min-P, Typical-P, repetition and seed", openGeneration)
    ComingSoon("Context", "Context size and maximum output tokens are fixed by the current native runtime.")
    ComingSoon("Thinking", "Requires model-provided reasoning output; unavailable in this build.")
    ComingSoon("Preset", "Presets are not saved until a persistent settings data store is implemented.")
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
  }
}

@Composable
private fun ModelScreen(modelName: String, modelStatus: String, loading: Boolean, onPick: () -> Unit, onUnload: () -> Unit, back: () -> Unit) {
  ScreenHeader("Model", back)
  Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
      Text(modelName, fontWeight = FontWeight.Bold); Text("GGUF · stored in app-private storage")
      Spacer(Modifier.height(8.dp)); Text(modelStatus, style = MaterialTheme.typography.bodySmall)
    } }
    Button(onClick = onPick, enabled = !loading, modifier = Modifier.fillMaxWidth().testTag("add_model_button")) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Model") }
    OutlinedButton(onClick = onUnload, enabled = !loading && modelStatus.startsWith("SUCCESS:"), modifier = Modifier.fillMaxWidth()) { Text("Unload") }
    Text("Only the active model is managed currently. Multi-model inventory and deletion are Coming Soon.", style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
private fun GenerationScreen(output: String, loading: Boolean, onGenerate: (SamplingSettings) -> Unit, back: () -> Unit) {
  var prompt by rememberSaveable { mutableStateOf("") }; var temperature by rememberSaveable { mutableStateOf("0.7") }
  var topK by rememberSaveable { mutableStateOf("40") }; var topP by rememberSaveable { mutableStateOf("0.9") }; var minP by rememberSaveable { mutableStateOf("0.0") }
  var typicalP by rememberSaveable { mutableStateOf("1.0") }; var repeat by rememberSaveable { mutableStateOf("1.1") }; var lastN by rememberSaveable { mutableStateOf("64") }; var seed by rememberSaveable { mutableStateOf("12345") }
  ScreenHeader("Generation", back)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("All controls below are sent directly to the native sampling pipeline.", style = MaterialTheme.typography.bodySmall)
    LabeledInput("Prompt", prompt, { prompt = it }, 3)
    LabeledInput("Temperature", temperature, { temperature = it }); LabeledInput("Top-K", topK, { topK = it }); LabeledInput("Top-P", topP, { topP = it })
    LabeledInput("Min-P", minP, { minP = it }); LabeledInput("Typical-P", typicalP, { typicalP = it }); LabeledInput("Repetition Penalty", repeat, { repeat = it }); LabeledInput("Penalty Last N", lastN, { lastN = it }); LabeledInput("Seed", seed, { seed = it })
    Button(onClick = { onGenerate(SamplingSettings(prompt, temperature.toFloatOrNull() ?: .7f, topK.toIntOrNull() ?: 40, topP.toFloatOrNull() ?: .9f, minP.toFloatOrNull() ?: 0f, typicalP.toFloatOrNull() ?: 1f, repeat.toFloatOrNull() ?: 1.1f, lastN.toIntOrNull() ?: 64, seed.toLongOrNull() ?: 12345L)) }, enabled = !loading && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("run_generation_button")) { Text(if (loading) "Generating…" else "Run local inference") }
    if (output.isNotBlank()) {
      Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(output, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
      }
    }
    HorizontalDivider(); Text("Coming Soon", fontWeight = FontWeight.Bold); Text("DRY · XTC · Dynamic Temperature · Mirostat · Frequency Penalty · Presence Penalty · Sampler Order", color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable private fun LabeledInput(label: String, value: String, change: (String) -> Unit, minLines: Int = 1) = OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)
@Composable private fun ScreenHeader(title: String, back: () -> Unit) = TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = back) { Text("Back") } })
@Composable private fun SettingCard(title: String, detail: String, click: () -> Unit) = Card(Modifier.fillMaxWidth(), onClick = click) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun ComingSoon(title: String, detail: String) = Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp)) { Text("Coming Soon · $title", fontWeight = FontWeight.Bold); Text(detail, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun UnavailableScreen(title: String, message: String, footnote: String) = Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Coming Soon", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(message); Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
