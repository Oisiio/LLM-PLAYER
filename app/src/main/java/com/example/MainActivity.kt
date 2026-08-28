package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  companion object {
    init {
      try {
        System.loadLibrary("localllm_native")
      } catch (e: UnsatisfiedLinkError) {
        e.printStackTrace()
      }
    }
  }

  private external fun stringFromJNI(): String
  private external fun nativeLoadModel(modelPath: String): String
  private external fun nativeUnloadModel()
  private external fun nativeIsModelLoaded(): Boolean

  private var modelStatus by mutableStateOf("モデル未ロード")
  private var selectedModelName by mutableStateOf("GGUFモデルを選択してください")

  private val openModelLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocument()
  ) { uri: Uri? ->
    if (uri == null) {
      modelStatus = "モデル選択をキャンセルしました"
      return@registerForActivityResult
    }

    selectedModelName = uri.lastPathSegment ?: "選択したモデル"
    modelStatus = "モデルをアプリ内部へコピー中..."

    lifecycleScope.launch {
      val result = copyAndLoadModel(uri)
      modelStatus = result
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val nativeMessage = try {
      stringFromJNI()
    } catch (t: Throwable) {
      "Native Load Error: ${t.message}"
    }

    setContent {
      MyApplicationTheme {
        LocalLLMApp(
          nativeMessage = nativeMessage,
          modelStatus = modelStatus,
          selectedModelName = selectedModelName,
          onSelectModel = { openModelLauncher.launch(arrayOf("application/octet-stream", "application/*")) }
        )
      }
    }
  }

  private suspend fun copyAndLoadModel(uri: Uri): String = withContext(Dispatchers.IO) {
    try {
      val modelsDir = File(filesDir, "models").apply { mkdirs() }
      val finalFile = File(modelsDir, "model.gguf")
      val tempFile = File(modelsDir, "model.gguf.partial")

      contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
          input.copyTo(output, DEFAULT_BUFFER_SIZE)
        }
      } ?: return@withContext "ERROR: モデルファイルを開けませんでした"

      if (!tempFile.renameTo(finalFile)) {
        tempFile.delete()
        return@withContext "ERROR: モデルファイルの確定に失敗しました"
      }

      val sizeBytes = finalFile.length()
      if (sizeBytes <= 0L) {
        finalFile.delete()
        return@withContext "ERROR: 空のモデルファイルです"
      }

      "${nativeLoadModel(finalFile.absolutePath)}\nPATH: ${finalFile.absolutePath}\nSIZE: ${sizeBytes} bytes"
    } catch (t: Throwable) {
      "ERROR: ${t.message ?: t.javaClass.simpleName}"
    }
  }

  override fun onDestroy() {
    try {
      nativeUnloadModel()
    } catch (t: Throwable) {
      t.printStackTrace()
    }
    super.onDestroy()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalLLMApp(
  nativeMessage: String = "Hello from native C++",
  modelStatus: String = "モデル未ロード",
  selectedModelName: String = "GGUFモデルを選択してください",
  onSelectModel: () -> Unit = {}
) {
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Outlined.Memory,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "LocalLLM",
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onBackground,
              modifier = Modifier.testTag("app_title")
            )
          }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface
        )
      )
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("build_test_card"),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Box(
            modifier = Modifier
              .size(76.dp)
              .shadow(8.dp, CircleShape, spotColor = MaterialTheme.colorScheme.primary)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary)
              .testTag("engine_icon"),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = "Validated Status",
              tint = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.size(38.dp)
            )
          }

          Spacer(modifier = Modifier.height(20.dp))

          Text(
            text = "Phase 3-B-1: GGUF Model Loading",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.testTag("build_test_label")
          )

          Spacer(modifier = Modifier.height(4.dp))

          Text(
            text = "JNI + llama.cpp model loader",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("phase_status_label")
          )

          Spacer(modifier = Modifier.height(16.dp))

          Button(
            onClick = onSelectModel,
            modifier = Modifier.fillMaxWidth().testTag("select_model_button")
          ) {
            Text("GGUFモデルを選択")
          }

          Spacer(modifier = Modifier.height(12.dp))

          Text(
            text = selectedModelName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Spacer(modifier = Modifier.height(12.dp))

          Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("native_message_container")
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "C++ NATIVE OUTPUT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = modelStatus,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("native_output_text")
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = nativeMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(50),
            modifier = Modifier.testTag("status_chip")
          ) {
            Text(
              text = "PHASE 3-B-1 ACTIVE",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
          }
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        SleekInfoCard(
          icon = Icons.Filled.PhoneAndroid,
          category = "Target Platform",
          value = "Android ARM64-v8a"
        )
        SleekInfoCard(
          icon = Icons.Filled.AccountTree,
          category = "Pipeline",
          value = "GitHub Actions Runner"
        )
      }
    }
  }
}

@Composable
private fun SleekInfoCard(
  icon: ImageVector,
  category: String,
  value: String
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp)
        )
      }

      Spacer(modifier = Modifier.width(16.dp))

      Column {
        Text(
          text = category.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = value,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface
        )
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun LocalLLMAppPreview() {
  MyApplicationTheme {
    LocalLLMApp()
  }
}
