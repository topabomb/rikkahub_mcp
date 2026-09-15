package net.weero.measix.pilot.ui.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.data.datastore.SettingsLockedException
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getCurrentAssistant
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.ai.AssistantPickerSheet
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.hooks.writeStringPreference
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.ui.theme.MeasixTheme
import net.weero.measix.pilot.ui.theme.WindowSystemBars
import net.weero.measix.pilot.utils.CrashHandler
import org.koin.android.ext.android.inject

class SafeModeActivity : ComponentActivity() {
    private val settingsStore by inject<SettingsStore>()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = CrashHandler.getStackTrace(this)
        CrashHandler.clearCrashed(this)
        enableEdgeToEdge()
        setContent {
            MeasixTheme {
                WindowSystemBars()
                val settings by settingsStore.userSettings.collectAsStateWithLifecycle()
                var showAssistantPicker by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val lockedMessage = stringResource(R.string.configuration_change_rejected, "{reason}")
                val toaster = rememberToasterState()
                val adaptiveLayoutInfo = rememberAdaptiveLayoutInfo()

                CompositionLocalProvider(
                    LocalAdaptiveLayoutInfo provides adaptiveLayoutInfo,
                    LocalToaster provides toaster,
                ) {
                    Toaster(
                        state = toaster,
                        darkTheme = LocalDarkMode.current,
                        richColors = true,
                        alignment = Alignment.TopCenter,
                        showCloseButton = true,
                    )
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            TopAppBar(title = { Text(stringResource(R.string.safe_mode_title)) })
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.safe_mode_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = stringResource(
                                    R.string.safe_mode_current_assistant,
                                    settings.getCurrentAssistant().name.ifEmpty {
                                        stringResource(R.string.safe_mode_default_assistant)
                                    },
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )

                            Button(
                                onClick = { showAssistantPicker = true },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.safe_mode_switch_assistant))
                            }

                            OutlinedButton(
                                onClick = {
                                    startActivity(Intent(this@SafeModeActivity, RouteActivity::class.java))
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.safe_mode_enter_app))
                            }

                            if (stackTrace != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = stringResource(R.string.safe_mode_crash_report),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(
                                                Context.CLIPBOARD_SERVICE,
                                            ) as ClipboardManager
                                            cm.setPrimaryClip(ClipData.newPlainText("crash", stackTrace))
                                        },
                                    ) {
                                        Text(stringResource(R.string.safe_mode_copy))
                                    }
                                }
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                ) {
                                    val vScroll = rememberScrollState()
                                    val hScroll = rememberScrollState()
                                    Text(
                                        text = stackTrace,
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .verticalScroll(vScroll)
                                            .horizontalScroll(hScroll),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }

                    if (showAssistantPicker) {
                        AssistantPickerSheet(
                            settings = settings,
                            currentAssistantId = settings.assistantId,
                            assistants = settings.assistants,
                            title = stringResource(R.string.safe_mode_assistants),
                            onAssistantSelected = { assistant ->
                                scope.launch {
                                    try {
                                        settingsStore.updateLocal { current -> current.copy(assistantId = assistant.id) }
                                    } catch (error: SettingsLockedException) {
                                        Toast.makeText(
                                            context,
                                            lockedMessage.replace("{reason}", error.reason),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        return@launch
                                    }
                                    context.writeStringPreference("lastConversationId", null)
                                    showAssistantPicker = false
                                }
                            },
                            onDismiss = { showAssistantPicker = false }
                        )
                    }
                }
            }
        }
    }
}
