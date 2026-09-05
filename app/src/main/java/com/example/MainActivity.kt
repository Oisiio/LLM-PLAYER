package com.example

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ui.talk.LlmStreamRunner
import com.example.ui.talk.TalkDebugMetrics
import com.example.ui.talk.TalkMainScreen
import com.example.ui.talk.TalkViewModel
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Destination { TALK, AI, AGENT }
private enum class AiPage { HOME, MODEL, GENERATION }

interface NativeTokenCallback {
  fun onToken(token: String)
  fun onTtft(ttftMs: Double) {}
  fun onMetrics(
    promptTokens: Int,
    genTokens: Int,
    promptTimeMs: Double,
    ttftMs: Double,
    genTimeMs: Double,
    totalTimeMs: Double,
    speed: Double,
    threads: Int
  ) {}
}

class MainActivity : ComponentActivity() {
  companion object { init { System.loadLibrary("localllm_native") } }

  private external fun nativeLoadModel(modelPath: String): String
  private external fun nativeUnloadModel()
  private external fun nativeIsModelLoaded(): Boolean
  private external fun nativeSetThreads(nThreads: Int, nThreadsBatch: Int)
  private external fun nativeSetContextSize(nCtx: Int): Boolean
  private external fun nativeGetContextSize(): Int
  private external fun nativeSetMaxOutputTokens(maxOutputTokens: Int): Boolean
  private external fun nativeGetMaxOutputTokens(): Int
  private external fun nativeGenerateWithSampling(
    prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
    typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
    enableThinking: Boolean
  ): String
  private external fun nativeGenerateStream(
    prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
    typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
    enableThinking: Boolean, callback: NativeTokenCallback
  ): String

  private var modelStatus by mutableStateOf("No model loaded")
  private var selectedModelName by mutableStateOf("No GGUF model selected")
  private var output by mutableStateOf("Select and load a GGUF model to begin.")
  private var loading by mutableStateOf(false)
  private var cpuThreads by mutableIntStateOf(4)
  private var cpuThreadsBatch by mutableIntStateOf(4)
  private var defaultTemperature by mutableFloatStateOf(0.7f)
  private var defaultTopK by mutableIntStateOf(40)
  private var defaultTopP by mutableFloatStateOf(0.9f)
  private var defaultContextSize by mutableIntStateOf(512)
  private var defaultMaxOutputTokens by mutableIntStateOf(128)

  private val talkViewModel by lazy {
    TalkViewModel(applicationContext, object : LlmStreamRunner {
      override fun isModelLoaded(): Boolean = nativeIsModelLoaded()
      override suspend fun runStreamingInference(
        prompt: String,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        typicalP: Float,
        repetitionPenalty: Float,
        penaltyLastN: Int,
        seed: Long,
        enableThinking: Boolean,
        onToken: (String) -> Unit,
        onTtft: ((Double) -> Unit)?,
        onMetrics: ((TalkDebugMetrics) -> Unit)?
      ): String = withContext(Dispatchers.Default) {
        if (!nativeIsModelLoaded()) return@withContext "ERROR: Model not loaded."
        nativeGenerateStream(
          prompt, temperature, topK, topP, minP, typicalP, repetitionPenalty,
          penaltyLastN, seed, enableThinking,
          object : NativeTokenCallback {
            override fun onToken(token: String) {
              onToken(token)
            }
            override fun onTtft(ttftMs: Double) {
              onTtft?.invoke(ttftMs)
            }
            override fun onMetrics(
              promptTokens: Int,
              genTokens: Int,
              promptTimeMs: Double,
              ttftMs: Double,
              genTimeMs: Double,
              totalTimeMs: Double,
              speed: Double,
              threads: Int
            ) {
              onMetrics?.invoke(
                TalkDebugMetrics(
                  ttftMs = ttftMs,
                  promptTokens = promptTokens,
                  genTokens = genTokens,
                  promptTimeMs = promptTimeMs,
                  genTimeMs = genTimeMs,
                  totalTimeMs = totalTimeMs,
                  speedTokPerSec = speed,
                  threads = threads,
                  isGenerating = false
                )
              )
            }
          }
        )
      }
    })
  }

  private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) lifecycleScope.launch {
      loading = true; output = ""
      modelStatus = copyAndLoadModel(uri)
      loading = false
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState); enableEdgeToEdge()
    val prefs = getSharedPreferences("talk_prefs", Context.MODE_PRIVATE)
    cpuThreads = prefs.getInt("cpu_threads", 4)
    cpuThreadsBatch = prefs.getInt("cpu_threads_batch", 4)
    defaultTemperature = talkViewModel.repository.getDefaultTemperature()
    defaultTopK = talkViewModel.repository.getDefaultTopK()
    defaultTopP = talkViewModel.repository.getDefaultTopP()
    defaultContextSize = talkViewModel.repository.getDefaultContextSize()
    defaultMaxOutputTokens = talkViewModel.repository.getDefaultMaxOutputTokens()
    nativeSetThreads(cpuThreads, cpuThreadsBatch)
    nativeSetContextSize(defaultContextSize)
    nativeSetMaxOutputTokens(defaultMaxOutputTokens)
    setContent {
      MyApplicationTheme {
        PlayerApp(
          talkViewModel = talkViewModel,
          modelStatus = modelStatus, modelName = selectedModelName, output = output, loading = loading,
          cpuThreads = cpuThreads,
          cpuThreadsBatch = cpuThreadsBatch,
          defaultTemperature = defaultTemperature,
          defaultTopK = defaultTopK,
          defaultTopP = defaultTopP,
          defaultContextSize = defaultContextSize,
          defaultMaxOutputTokens = defaultMaxOutputTokens,
          onUpdateThreads = { threads, batchThreads ->
            cpuThreads = threads
            cpuThreadsBatch = batchThreads
            nativeSetThreads(threads, batchThreads)
            prefs.edit()
              .putInt("cpu_threads", threads)
              .putInt("cpu_threads_batch", batchThreads)
              .apply()
          },
          onUpdateDefaultTemperature = { temp ->
            defaultTemperature = temp
            talkViewModel.repository.setDefaultTemperature(temp)
          },
          onUpdateDefaultTopK = { k ->
            defaultTopK = k
            talkViewModel.repository.setDefaultTopK(k)
          },
          onUpdateDefaultTopP = { p ->
            defaultTopP = p
            talkViewModel.repository.setDefaultTopP(p)
          },
          onUpdateDefaultContextSize = { size ->
            defaultContextSize = size
            talkViewModel.repository.setDefaultContextSize(size)
            nativeSetContextSize(size)
          },
          onUpdateDefaultMaxOutputTokens = { tokens ->
            defaultMaxOutputTokens = tokens
            talkViewModel.repository.setDefaultMaxOutputTokens(tokens)
            nativeSetMaxOutputTokens(tokens)
          },
          onPickModel = { modelPicker.launch(arrayOf("application/octet-stream", "application/*")) },
          onUnload = { nativeUnloadModel(); modelStatus = "No model loaded"; output = "Model unloaded." },
          onGenerate = { settings ->
            loading = true
            lifecycleScope.launch {
              output = withContext(Dispatchers.Default) {
                if (!nativeIsModelLoaded()) "ERROR: Load a GGUF model in AI > Model first."
                else nativeGenerateWithSampling(settings.prompt, settings.temperature, settings.topK, settings.topP,
                  settings.minP, settings.typicalP, settings.repetitionPenalty, settings.penaltyLastN, settings.seed,
                  settings.enableThinking)
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
  val typicalP: Float, val repetitionPenalty: Float, val penaltyLastN: Int, val seed: Long,
  val enableThinking: Boolean = false
)

@Composable
private fun PlayerApp(
  talkViewModel: TalkViewModel, modelStatus: String, modelName: String, output: String, loading: Boolean,
  cpuThreads: Int, cpuThreadsBatch: Int,
  defaultTemperature: Float, defaultTopK: Int, defaultTopP: Float,
  defaultContextSize: Int,
  defaultMaxOutputTokens: Int,
  onUpdateThreads: (Int, Int) -> Unit,
  onUpdateDefaultTemperature: (Float) -> Unit,
  onUpdateDefaultTopK: (Int) -> Unit,
  onUpdateDefaultTopP: (Float) -> Unit,
  onUpdateDefaultContextSize: (Int) -> Unit,
  onUpdateDefaultMaxOutputTokens: (Int) -> Unit,
  onPickModel: () -> Unit, onUnload: () -> Unit, onGenerate: (SamplingSettings) -> Unit
) {
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
        Destination.TALK -> TalkMainScreen(talkViewModel)
        Destination.AGENT -> UnavailableScreen("Agent", "Agent runs, tools, and memory require a native harness and are not available in this build.", "No tool controls are exposed until they can execute safely.")
        Destination.AI -> when (aiPage) {
          AiPage.HOME -> AiHome(
            modelName, modelStatus, loading,
            cpuThreads, cpuThreadsBatch, onUpdateThreads,
            defaultTemperature, defaultTopK, defaultTopP,
            onUpdateDefaultTemperature, onUpdateDefaultTopK, onUpdateDefaultTopP,
            defaultContextSize, onUpdateDefaultContextSize,
            defaultMaxOutputTokens, onUpdateDefaultMaxOutputTokens,
            { aiPage = AiPage.MODEL }, { aiPage = AiPage.GENERATION }
          )
          AiPage.MODEL -> ModelScreen(modelName, modelStatus, loading, onPickModel, onUnload, { aiPage = AiPage.HOME })
          AiPage.GENERATION -> GenerationScreen(
            output, loading,
            defaultTemperature, defaultTopK, defaultTopP,
            onGenerate, { aiPage = AiPage.HOME }
          )
        }
      }
    }
  }
}

@Composable
private fun RowScope.NavItem(target: Destination, selected: Destination, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
  NavigationBarItem(selected = target == selected, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) })
}

@Composable
private fun AiHome(
  modelName: String, modelStatus: String, loading: Boolean,
  cpuThreads: Int, cpuThreadsBatch: Int, onUpdateThreads: (Int, Int) -> Unit,
  defaultTemperature: Float, defaultTopK: Int, defaultTopP: Float,
  onUpdateDefaultTemperature: (Float) -> Unit,
  onUpdateDefaultTopK: (Int) -> Unit,
  onUpdateDefaultTopP: (Float) -> Unit,
  defaultContextSize: Int,
  onUpdateDefaultContextSize: (Int) -> Unit,
  defaultMaxOutputTokens: Int,
  onUpdateDefaultMaxOutputTokens: (Int) -> Unit,
  openModel: () -> Unit, openGeneration: () -> Unit
) {
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Configure the local LLM engine. Settings shown here are passed to the native sampler.")

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("コンテキストサイズ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text("$defaultContextSize tokens", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(
          "会話で一度に扱えるトークン数です。大きくすると長いプロンプトを扱えます。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(512, 1024, 2048, 4096).forEach { size ->
            FilterChip(
              selected = defaultContextSize == size,
              onClick = { onUpdateDefaultContextSize(size) },
              label = { Text("$size") },
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
          Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Max Output Tokens", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
          Text("$defaultMaxOutputTokens tokens", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Text(
          "一度の生成で出力できる最大トークン数です。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(64, 128, 256, 512).forEach { tokens ->
            FilterChip(
              selected = defaultMaxOutputTokens == tokens,
              onClick = { onUpdateDefaultMaxOutputTokens(tokens) },
              label = { Text("$tokens") },
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("CPUスレッド設定", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("CPUスレッド数", fontWeight = FontWeight.SemiBold)
            Text("$cpuThreads", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Text("CPUスレッド数：テキスト生成時に使用するCPUスレッド数です。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Slider(
            value = cpuThreads.toFloat(),
            onValueChange = { onUpdateThreads(it.toInt(), cpuThreadsBatch) },
            valueRange = 1f..8f,
            steps = 6
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("バッチスレッド数", fontWeight = FontWeight.SemiBold)
            Text("$cpuThreadsBatch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Text("バッチスレッド数：プロンプト処理時に使用するCPUスレッド数です。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          Slider(
            value = cpuThreadsBatch.toFloat(),
            onValueChange = { onUpdateThreads(cpuThreads, it.toInt()) },
            valueRange = 1f..8f,
            steps = 6
          )
        }
      }
    }

    Card(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("デフォルト生成パラメータ設定", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text("新規作成チャットおよび生成テスト画面の初期サンプリング設定です。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Temperature", fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "%.2f", defaultTemperature), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Slider(
            value = defaultTemperature,
            onValueChange = {
              val rounded = (Math.round(it * 20.0f) / 20.0f).coerceIn(0.0f, 2.0f)
              onUpdateDefaultTemperature(rounded)
            },
            valueRange = 0.0f..2.0f,
            steps = 39
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Top-K", fontWeight = FontWeight.SemiBold)
            Text("$defaultTopK", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Slider(
            value = defaultTopK.toFloat(),
            onValueChange = {
              onUpdateDefaultTopK(it.toInt().coerceIn(1, 100))
            },
            valueRange = 1f..100f,
            steps = 98
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Top-P", fontWeight = FontWeight.SemiBold)
            Text(String.format(Locale.US, "%.2f", defaultTopP), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
          Slider(
            value = defaultTopP,
            onValueChange = {
              val rounded = (Math.round(it * 20.0f) / 20.0f).coerceIn(0.05f, 1.0f)
              onUpdateDefaultTopP(rounded)
            },
            valueRange = 0.05f..1.0f,
            steps = 18
          )
        }
      }
    }

    SettingCard("Model", if (modelStatus.startsWith("SUCCESS:")) "$modelName · Loaded" else modelStatus, openModel)
    SettingCard("Generation", "Thinking ON/OFF, Temperature, Top-K, Top-P, Min-P, Typical-P, repetition, seed", openGeneration)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenerationScreen(
  output: String,
  loading: Boolean,
  initialTemperature: Float,
  initialTopK: Int,
  initialTopP: Float,
  onGenerate: (SamplingSettings) -> Unit,
  back: () -> Unit
) {
  var prompt by rememberSaveable { mutableStateOf("") }
  var temperature by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialTemperature)) }
  var topK by rememberSaveable { mutableStateOf(initialTopK.toString()) }
  var topP by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialTopP)) }
  var minP by rememberSaveable { mutableStateOf("0.0") }
  var typicalP by rememberSaveable { mutableStateOf("1.0") }
  var repeat by rememberSaveable { mutableStateOf("1.1") }
  var lastN by rememberSaveable { mutableStateOf("64") }
  var seed by rememberSaveable { mutableStateOf("12345") }
  var enableThinking by rememberSaveable { mutableStateOf(false) }
  ScreenHeader("Generation", back)
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text("All controls below are sent directly to the native sampling pipeline.", style = MaterialTheme.typography.bodySmall)
    LabeledInput("Prompt", prompt, { prompt = it }, 3)
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text("Thinking", fontWeight = FontWeight.Bold)
        Text(if (enableThinking) "ON" else "OFF", style = MaterialTheme.typography.bodySmall)
      }
      Switch(
        checked = enableThinking,
        onCheckedChange = { enableThinking = it },
        modifier = Modifier.testTag("thinking_switch")
      )
    }
    LabeledInput("Temperature", temperature, { temperature = it }); LabeledInput("Top-K", topK, { topK = it }); LabeledInput("Top-P", topP, { topP = it })
    LabeledInput("Min-P", minP, { minP = it }); LabeledInput("Typical-P", typicalP, { typicalP = it }); LabeledInput("Repetition Penalty", repeat, { repeat = it }); LabeledInput("Penalty Last N", lastN, { lastN = it }); LabeledInput("Seed", seed, { seed = it })
    Button(onClick = { onGenerate(SamplingSettings(prompt, temperature.toFloatOrNull() ?: .7f, topK.toIntOrNull() ?: 40, topP.toFloatOrNull() ?: .9f, minP.toFloatOrNull() ?: 0f, typicalP.toFloatOrNull() ?: 1f, repeat.toFloatOrNull() ?: 1.1f, lastN.toIntOrNull() ?: 64, seed.toLongOrNull() ?: 12345L, enableThinking)) }, enabled = !loading && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("run_generation_button")) { Text(if (loading) "Generating…" else "Run local inference") }
    if (output.isNotBlank()) {
      Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(output, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
      }
    }
    HorizontalDivider(); Text("Coming Soon", fontWeight = FontWeight.Bold); Text("DRY · XTC · Dynamic Temperature · Mirostat · Frequency Penalty · Presence Penalty · Sampler Order", color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable private fun LabeledInput(label: String, value: String, change: (String) -> Unit, minLines: Int = 1) = OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ScreenHeader(title: String, back: () -> Unit) {
  TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(onClick = back) { Text("Back") } })
}

@Composable private fun SettingCard(title: String, detail: String, click: () -> Unit) = Card(onClick = click, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(detail, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun ComingSoon(title: String, detail: String) = Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp)) { Text("Coming Soon · $title", fontWeight = FontWeight.Bold); Text(detail, style = MaterialTheme.typography.bodySmall) } }
@Composable private fun UnavailableScreen(title: String, message: String, footnote: String) = Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Coming Soon", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Text(message); Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
