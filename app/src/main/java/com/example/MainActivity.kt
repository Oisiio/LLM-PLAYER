package com.example

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.agent.AgentPreferences
import com.example.agent.AgentRunner
import com.example.ui.agent.AgentScreen
import com.example.ui.home.HomeScreen
import com.example.ui.navigation.AppDestination
import com.example.ui.navigation.DrawerContent
import com.example.ui.settings.*
import com.example.ui.talk.LlmStreamRunner
import com.example.ui.talk.TalkDebugMetrics
import com.example.ui.talk.TalkMainScreen
import com.example.ui.talk.TalkViewModel
import com.example.ui.theme.*
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



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
  private external fun nativeCancelGeneration()
  private external fun nativeCancelAiGeneration()
  private external fun nativeGenerateWithSampling(
    prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
    typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
    enableThinking: Boolean
  ): String
  private external fun nativeGenerateStream(
    prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
    typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
    enableThinking: Boolean, thinkingBudget: Int, callback: NativeTokenCallback
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
  private var defaultMinP by mutableFloatStateOf(0.0f)
  private var defaultTypicalP by mutableFloatStateOf(1.0f)
  private var defaultRepetitionPenalty by mutableFloatStateOf(1.1f)
  private var defaultPenaltyLastN by mutableIntStateOf(64)
  private var defaultContextSize by mutableIntStateOf(512)
  private var defaultMaxOutputTokens by mutableIntStateOf(128)
  private var generationSeed by mutableStateOf("12345")

  private val llmStreamRunner: LlmStreamRunner by lazy {
    object : LlmStreamRunner {
      override fun isModelLoaded(): Boolean = nativeIsModelLoaded()
      override fun cancelGeneration() { nativeCancelGeneration() }
      override suspend fun runStreamingInference(
        prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
        typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
        enableThinking: Boolean, onToken: (String) -> Unit,
        onTtft: ((Double) -> Unit)?, onMetrics: ((TalkDebugMetrics) -> Unit)?
      ): String = runStreamingInference(
        prompt, temperature, topK, topP, minP, typicalP, repetitionPenalty,
        penaltyLastN, seed, enableThinking, 0, onToken, onTtft, onMetrics
      )

      override suspend fun runStreamingInference(
        prompt: String, temperature: Float, topK: Int, topP: Float, minP: Float,
        typicalP: Float, repetitionPenalty: Float, penaltyLastN: Int, seed: Long,
        enableThinking: Boolean, thinkingBudget: Int, onToken: (String) -> Unit,
        onTtft: ((Double) -> Unit)?, onMetrics: ((TalkDebugMetrics) -> Unit)?
      ): String = withContext(Dispatchers.Default) {
        if (!nativeIsModelLoaded()) return@withContext "ERROR: Model not loaded."
        nativeGenerateStream(
          prompt, temperature, topK, topP, minP, typicalP, repetitionPenalty,
          penaltyLastN, seed, enableThinking, thinkingBudget,
          object : NativeTokenCallback {
            override fun onToken(token: String) { onToken(token) }
            override fun onTtft(ttftMs: Double) { onTtft?.invoke(ttftMs) }
            override fun onMetrics(
              promptTokens: Int, genTokens: Int, promptTimeMs: Double,
              ttftMs: Double, genTimeMs: Double, totalTimeMs: Double,
              speed: Double, threads: Int
            ) {
              onMetrics?.invoke(TalkDebugMetrics(
                ttftMs = ttftMs, promptTokens = promptTokens, genTokens = genTokens,
                promptTimeMs = promptTimeMs, genTimeMs = genTimeMs,
                totalTimeMs = totalTimeMs, speedTokPerSec = speed,
                threads = threads, isGenerating = false
              ))
            }
          }
        )
      }
    }
  }

  private val talkViewModel by lazy {
    TalkViewModel(applicationContext, llmStreamRunner)
  }

  private val agentRunner by lazy {
    AgentRunner(llmStreamRunner)
  }

  private val agentPreferences by lazy {
    AgentPreferences(applicationContext)
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
    defaultMinP = talkViewModel.repository.getDefaultMinP()
    defaultTypicalP = talkViewModel.repository.getDefaultTypicalP()
    defaultRepetitionPenalty = talkViewModel.repository.getDefaultRepetitionPenalty()
    defaultPenaltyLastN = talkViewModel.repository.getDefaultPenaltyLastN()
    defaultContextSize = talkViewModel.repository.getDefaultContextSize()
    defaultMaxOutputTokens = talkViewModel.repository.getDefaultMaxOutputTokens()
    generationSeed = prefs.getString("generation_seed", "12345") ?: "12345"
    nativeSetThreads(cpuThreads, cpuThreadsBatch)
    nativeSetContextSize(defaultContextSize)
    nativeSetMaxOutputTokens(defaultMaxOutputTokens)
    setContent {
      MyApplicationTheme {
        PlayerApp(
          talkViewModel = talkViewModel,
          modelStatus = modelStatus, modelName = selectedModelName, output = output, loading = loading,
          cpuThreads = cpuThreads, cpuThreadsBatch = cpuThreadsBatch,
          defaultTemperature = defaultTemperature, defaultTopK = defaultTopK, defaultTopP = defaultTopP,
          defaultMinP = defaultMinP, defaultTypicalP = defaultTypicalP,
          defaultRepetitionPenalty = defaultRepetitionPenalty, defaultPenaltyLastN = defaultPenaltyLastN,
          defaultContextSize = defaultContextSize, defaultMaxOutputTokens = defaultMaxOutputTokens,
          generationSeed = generationSeed,
          onUpdateThreads = { threads, batchThreads ->
            cpuThreads = threads; cpuThreadsBatch = batchThreads
            nativeSetThreads(threads, batchThreads)
            prefs.edit().putInt("cpu_threads", threads).putInt("cpu_threads_batch", batchThreads).apply()
          },
          onUpdateDefaultTemperature = { temp -> defaultTemperature = temp; talkViewModel.repository.setDefaultTemperature(temp) },
          onUpdateDefaultTopK = { k -> defaultTopK = k; talkViewModel.repository.setDefaultTopK(k) },
          onUpdateDefaultTopP = { p -> defaultTopP = p; talkViewModel.repository.setDefaultTopP(p) },
          onUpdateDefaultMinP = { p -> defaultMinP = p; talkViewModel.repository.setDefaultMinP(p) },
          onUpdateDefaultTypicalP = { p -> defaultTypicalP = p; talkViewModel.repository.setDefaultTypicalP(p) },
          onUpdateDefaultRepetitionPenalty = { p -> defaultRepetitionPenalty = p; talkViewModel.repository.setDefaultRepetitionPenalty(p) },
          onUpdateDefaultPenaltyLastN = { n -> defaultPenaltyLastN = n; talkViewModel.repository.setDefaultPenaltyLastN(n) },
          onUpdateDefaultContextSize = { size ->
            defaultContextSize = size; talkViewModel.repository.setDefaultContextSize(size); nativeSetContextSize(size)
          },
          onUpdateDefaultMaxOutputTokens = { tokens ->
            defaultMaxOutputTokens = tokens; talkViewModel.repository.setDefaultMaxOutputTokens(tokens); nativeSetMaxOutputTokens(tokens)
          },
          onUpdateGenerationSeed = { seed -> generationSeed = seed; prefs.edit().putString("generation_seed", seed).apply() },
          agentPreferences = agentPreferences,
          onResetAllSettings = {
            talkViewModel.repository.resetToDefaults()
            agentPreferences.resetAll()
            prefs.edit().clear().apply()
            cpuThreads = 4
            cpuThreadsBatch = 4
            defaultTemperature = 0.8f
            defaultTopK = 40
            defaultTopP = 0.9f
            defaultMinP = 0.05f
            defaultTypicalP = 1.0f
            defaultRepetitionPenalty = 1.1f
            defaultPenaltyLastN = 64
            defaultContextSize = 8192
            defaultMaxOutputTokens = 512
            generationSeed = "12345"
            nativeSetThreads(4, 4)
            nativeSetContextSize(8192)
            nativeSetMaxOutputTokens(512)
          },
          onPickModel = { modelPicker.launch(arrayOf("application/octet-stream", "application/*")) },
          onUnload = { nativeUnloadModel(); modelStatus = "No model loaded"; output = "Model unloaded." },
          onCancelGenerate = { nativeCancelAiGeneration() },
          onGenerate = { settings ->
            loading = true
            lifecycleScope.launch {
              output = withContext(Dispatchers.Default) {
                if (!nativeIsModelLoaded()) "ERROR: Load a GGUF model in AI > Model first."
                else nativeGenerateWithSampling(
                  settings.prompt, settings.temperature, settings.topK, settings.topP,
                  settings.minP, settings.typicalP, settings.repetitionPenalty,
                  settings.penaltyLastN, settings.seed, settings.enableThinking
                )
              }
              loading = false
            }
          },
          agentRunner = agentRunner,
          isModelLoaded = nativeIsModelLoaded()
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
  talkViewModel: TalkViewModel,
  modelStatus: String,
  modelName: String,
  output: String,
  loading: Boolean,
  cpuThreads: Int,
  cpuThreadsBatch: Int,
  defaultTemperature: Float,
  defaultTopK: Int,
  defaultTopP: Float,
  defaultMinP: Float,
  defaultTypicalP: Float,
  defaultRepetitionPenalty: Float,
  defaultPenaltyLastN: Int,
  defaultContextSize: Int,
  defaultMaxOutputTokens: Int,
  generationSeed: String,
  agentPreferences: AgentPreferences,
  onUpdateThreads: (Int, Int) -> Unit,
  onUpdateDefaultTemperature: (Float) -> Unit,
  onUpdateDefaultTopK: (Int) -> Unit,
  onUpdateDefaultTopP: (Float) -> Unit,
  onUpdateDefaultMinP: (Float) -> Unit,
  onUpdateDefaultTypicalP: (Float) -> Unit,
  onUpdateDefaultRepetitionPenalty: (Float) -> Unit,
  onUpdateDefaultPenaltyLastN: (Int) -> Unit,
  onUpdateDefaultContextSize: (Int) -> Unit,
  onUpdateDefaultMaxOutputTokens: (Int) -> Unit,
  onUpdateGenerationSeed: (String) -> Unit,
  onResetAllSettings: () -> Unit,
  onPickModel: () -> Unit,
  onUnload: () -> Unit,
  onCancelGenerate: () -> Unit,
  onGenerate: (SamplingSettings) -> Unit,
  agentRunner: AgentRunner,
  isModelLoaded: Boolean
) {
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val coroutineScope = rememberCoroutineScope()

  var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
  var settingsSubpage by rememberSaveable { mutableStateOf(SettingsSubpage.ROOT) }
  var isGenerationTestOpen by rememberSaveable { mutableStateOf(false) }

  // Handle system back navigation
  BackHandler(enabled = drawerState.isOpen || isGenerationTestOpen || (destination == AppDestination.SETTINGS && settingsSubpage != SettingsSubpage.ROOT) || destination != AppDestination.HOME) {
    if (drawerState.isOpen) {
      coroutineScope.launch { drawerState.close() }
    } else if (isGenerationTestOpen) {
      isGenerationTestOpen = false
    } else if (destination == AppDestination.SETTINGS && settingsSubpage != SettingsSubpage.ROOT) {
      settingsSubpage = SettingsSubpage.ROOT
    } else if (destination != AppDestination.HOME) {
      destination = AppDestination.HOME
    }
  }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      DrawerContent(
        currentDestination = destination,
        onSelectDestination = { dest ->
          destination = dest
          if (dest == AppDestination.SETTINGS) {
            settingsSubpage = SettingsSubpage.ROOT
          }
          isGenerationTestOpen = false
          coroutineScope.launch { drawerState.close() }
        }
      )
    },
    scrimColor = Color.Black.copy(alpha = 0.6f)
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = LlmBackground) {
      if (isGenerationTestOpen) {
        GenerationScreen(
          output = output,
          loading = loading,
          initialTemperature = defaultTemperature,
          initialTopK = defaultTopK,
          initialTopP = defaultTopP,
          initialMinP = defaultMinP,
          initialTypicalP = defaultTypicalP,
          initialRepetitionPenalty = defaultRepetitionPenalty,
          initialPenaltyLastN = defaultPenaltyLastN,
          seed = generationSeed,
          onSeedChange = onUpdateGenerationSeed,
          onCancel = onCancelGenerate,
          onGenerate = onGenerate,
          back = { isGenerationTestOpen = false }
        )
      } else {
        when (destination) {
          AppDestination.HOME -> HomeScreen(
            isModelLoaded = isModelLoaded,
            modelName = modelName,
            modelStatus = modelStatus,
            contextSize = defaultContextSize,
            cpuThreads = cpuThreads,
            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
            onNavigate = { dest ->
              destination = dest
              if (dest == AppDestination.SETTINGS) {
                settingsSubpage = SettingsSubpage.ROOT
              }
            },
            onPickModel = onPickModel,
            onOpenGenerationTest = { isGenerationTestOpen = true }
          )

          AppDestination.CHAT -> TalkMainScreen(
            viewModel = talkViewModel,
            onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
          )

          AppDestination.AGENT -> AgentScreen(
            agentRunner = agentRunner,
            isModelLoaded = isModelLoaded,
            onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
          )

          AppDestination.MODELS -> ModelScreen(
            modelName = modelName,
            modelStatus = modelStatus,
            loading = loading,
            onPick = onPickModel,
            onUnload = onUnload,
            onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
          )

          AppDestination.SETTINGS -> when (settingsSubpage) {
            SettingsSubpage.ROOT -> SettingsScreen(
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onNavigateSubpage = { sub -> settingsSubpage = sub }
            )

            SettingsSubpage.INFERENCE -> InferenceSettingsScreen(
              currentContextSize = defaultContextSize,
              currentMaxOutputTokens = defaultMaxOutputTokens,
              currentTemperature = defaultTemperature,
              currentTopK = defaultTopK,
              currentTopP = defaultTopP,
              currentMinP = defaultMinP,
              currentTypicalP = defaultTypicalP,
              currentRepetitionPenalty = defaultRepetitionPenalty,
              currentPenaltyLastN = defaultPenaltyLastN,
              modelName = modelName,
              modelStatus = modelStatus,
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onBack = { settingsSubpage = SettingsSubpage.ROOT },
              onApply = { ctx, maxTok, temp, k, p, minP, typP, rep, lastN ->
                onUpdateDefaultContextSize(ctx)
                onUpdateDefaultMaxOutputTokens(maxTok)
                onUpdateDefaultTemperature(temp)
                onUpdateDefaultTopK(k)
                onUpdateDefaultTopP(p)
                onUpdateDefaultMinP(minP)
                onUpdateDefaultTypicalP(typP)
                onUpdateDefaultRepetitionPenalty(rep)
                onUpdateDefaultPenaltyLastN(lastN)
              }
            )

            SettingsSubpage.AGENT -> AgentSettingsScreen(
              currentThinkingEnabled = agentPreferences.isThinkingEnabled,
              currentThinkingBudget = agentPreferences.thinkingBudget,
              currentCalculatorEnabled = agentPreferences.isCalculatorEnabled,
              currentDateTimeEnabled = agentPreferences.isDateTimeEnabled,
              currentMaxSteps = agentPreferences.maxSteps,
              currentMaxOutputTokens = defaultMaxOutputTokens,
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onBack = { settingsSubpage = SettingsSubpage.ROOT },
              onApply = { thinking, budget, calc, dt, steps ->
                agentPreferences.isThinkingEnabled = thinking
                agentPreferences.thinkingBudget = budget
                agentPreferences.isCalculatorEnabled = calc
                agentPreferences.isDateTimeEnabled = dt
                agentPreferences.maxSteps = steps
                agentRunner.configureTools(calc, dt)
              }
            )

            SettingsSubpage.PERFORMANCE -> PerformanceSettingsScreen(
              currentCpuThreads = cpuThreads,
              currentCpuThreadsBatch = cpuThreadsBatch,
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onBack = { settingsSubpage = SettingsSubpage.ROOT },
              onApply = { threads, batchThreads ->
                onUpdateThreads(threads, batchThreads)
              }
            )

            SettingsSubpage.ADVANCED -> AdvancedSettingsScreen(
              cpuThreads = cpuThreads,
              contextSize = defaultContextSize,
              maxOutputTokens = defaultMaxOutputTokens,
              modelStatus = modelStatus,
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onBack = { settingsSubpage = SettingsSubpage.ROOT },
              onResetAllSettings = onResetAllSettings,
              onApply = { _, _, _ -> }
            )

            SettingsSubpage.GENERAL -> GeneralSettingsScreen(
              currentViewMode = talkViewModel.viewMode.collectAsState().value,
              onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
              onBack = { settingsSubpage = SettingsSubpage.ROOT },
              onApply = { mode ->
                talkViewModel.repository.setViewMode(mode)
              }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ModelScreen(
  modelName: String,
  modelStatus: String,
  loading: Boolean,
  onPick: () -> Unit,
  onUnload: () -> Unit,
  onOpenDrawer: () -> Unit
) {
  Scaffold(
    topBar = {
      LlmScreenHeader(
        title = "Models",
        icon = Icons.Default.Memory,
        onOpenDrawer = onOpenDrawer
      )
    },
    containerColor = LlmBackground
  ) { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      LlmIntroSection(
        title = "モデル管理",
        description = "オンデバイス推論に使用するGGUFモデルを管理します"
      )

      LlmSettingsCard(
        title = "Active Model",
        badge = if (modelStatus.startsWith("SUCCESS:")) "LOADED" else "NO MODEL"
      ) {
        Text(modelName, fontWeight = FontWeight.Bold, color = LlmTextPrimary)
        Text("GGUF · アプリ内部ストレージに保存", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
          modelStatus,
          style = MaterialTheme.typography.bodySmall,
          color = if (modelStatus.startsWith("SUCCESS:")) LlmSuccess else LlmTextSecondary
        )
      }

      Button(
        onClick = onPick,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("add_model_button"),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LlmPrimary, contentColor = Color(0xFF0F101A))
      ) {
        Icon(Icons.Filled.Add, null)
        Spacer(Modifier.width(8.dp))
        Text("モデルを追加・選択", fontWeight = FontWeight.Bold)
      }

      if (modelStatus.startsWith("SUCCESS:")) {
        OutlinedButton(
          onClick = onUnload,
          enabled = !loading,
          modifier = Modifier.fillMaxWidth().height(44.dp),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("モデルをアンロード", color = MaterialTheme.colorScheme.error)
        }
      }

      if (loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = LlmPrimary)
      }

      Text(
        "※ 現在はアクティブな1モデルの管理に対応しています。複数モデルの切り替え・削除機能は順次拡張予定です。",
        style = MaterialTheme.typography.bodySmall,
        color = LlmTextTertiary
      )
    }
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
  initialMinP: Float,
  initialTypicalP: Float,
  initialRepetitionPenalty: Float,
  initialPenaltyLastN: Int,
  seed: String,
  onSeedChange: (String) -> Unit,
  onCancel: () -> Unit,
  onGenerate: (SamplingSettings) -> Unit,
  back: () -> Unit
) {
  var prompt by rememberSaveable { mutableStateOf("") }
  var temperature by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialTemperature)) }
  var topK by rememberSaveable { mutableStateOf(initialTopK.toString()) }
  var topP by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialTopP)) }
  var minP by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialMinP)) }
  var typicalP by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialTypicalP)) }
  var repeat by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", initialRepetitionPenalty)) }
  var lastN by rememberSaveable { mutableStateOf(initialPenaltyLastN.toString()) }
  var enableThinking by rememberSaveable { mutableStateOf(false) }

  Scaffold(
    topBar = {
      LlmScreenHeader(
        title = "Playground",
        onOpenDrawer = {},
        onBack = back
      )
    },
    containerColor = LlmBackground
  ) { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      Text("All controls below are sent directly to the native sampling pipeline.", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary)
      LabeledInput("Prompt", prompt, { prompt = it }, 3)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("Thinking", fontWeight = FontWeight.Bold, color = LlmTextPrimary); Text(if (enableThinking) "ON" else "OFF", style = MaterialTheme.typography.bodySmall, color = LlmTextSecondary) }
        Switch(checked = enableThinking, onCheckedChange = { enableThinking = it }, modifier = Modifier.testTag("thinking_switch"))
      }
      LabeledInput("Temperature", temperature, { temperature = it })
      LabeledInput("Top-K", topK, { topK = it })
      LabeledInput("Top-P", topP, { topP = it })
      LabeledInput("Min-P", minP, { minP = it })
      LabeledInput("Typical-P", typicalP, { typicalP = it })
      LabeledInput("Repetition Penalty", repeat, { repeat = it })
      LabeledInput("Penalty Last N", lastN, { lastN = it })
      LabeledInput("Seed", seed, onSeedChange)
      Button(
        onClick = {
          if (loading) onCancel() else onGenerate(SamplingSettings(
            prompt, temperature.toFloatOrNull() ?: .7f, topK.toIntOrNull() ?: 40,
            topP.toFloatOrNull() ?: .9f, minP.toFloatOrNull() ?: 0f,
            typicalP.toFloatOrNull() ?: 1f, repeat.toFloatOrNull() ?: 1.1f,
            lastN.toIntOrNull() ?: 64, seed.toLongOrNull() ?: 12345L, enableThinking
          ))
        },
        enabled = loading || prompt.isNotBlank(),
        colors = if (loading) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(containerColor = LlmPrimary, contentColor = Color(0xFF0F101A)),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("run_generation_button")
      ) {
        Text(if (loading) "Stop Generation" else "Run local inference", fontWeight = FontWeight.Bold)
      }
      if (output.isNotBlank()) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = LlmContainer) {
          Text(output, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = LlmTextPrimary)
        }
      }
    }
  }
}

@Composable
private fun PersistentNumberInput(
  label: String,
  value: String,
  keyboardType: KeyboardType,
  onCommit: (String) -> Boolean
) {
  val focusManager = LocalFocusManager.current
  var isFocused by remember { mutableStateOf(false) }
  var text by remember { mutableStateOf(value) }

  val currentText by rememberUpdatedState(text)
  val currentValue by rememberUpdatedState(value)
  val currentOnCommit by rememberUpdatedState(onCommit)

  LaunchedEffect(value, isFocused) {
    if (!isFocused) {
      text = value
    }
  }

  val commit = remember {
    {
      val trimmed = currentText.trim()
      if (trimmed.isNotEmpty()) {
        val success = currentOnCommit(trimmed)
        if (!success) {
          text = currentValue
        }
      } else {
        text = currentValue
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      commit()
    }
  }

  OutlinedTextField(
    value = text,
    onValueChange = { text = it },
    modifier = Modifier
      .fillMaxWidth()
      .onFocusChanged { state ->
        if (isFocused && !state.isFocused) {
          commit()
        }
        isFocused = state.isFocused
      },
    label = { Text(label) },
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
    keyboardActions = KeyboardActions(
      onDone = {
        commit()
        focusManager.clearFocus()
      }
    )
  )
}

@Composable
private fun LabeledInput(label: String, value: String, change: (String) -> Unit, minLines: Int = 1) = OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)


