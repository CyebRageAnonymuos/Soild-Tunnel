package com.soildtunnel.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.soildtunnel.app.ui.theme.SoildTunnelTheme
import java.io.File

/**
 * Shows the fatal JVM exception saved by [SoildTunnelApp]'s crash handler on the
 * previous run. Ported from the merged CrashReportScreen, kept as a separate
 * Activity (launched on the next cold start) so the report is reachable even
 * when the crash broke the home UI's state.
 */
class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "last_crash.txt")
        val details = runCatching { crashFile.readText() }.getOrDefault("")

        setContent {
            SoildTunnelTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.crash_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            Text(
                                text = details.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            )
                        }
                        val clipboard = LocalClipboardManager.current
                        var copied by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(details))
                                    copied = true
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (copied) R.string.crash_copied else R.string.crash_copy,
                                    ),
                                )
                            }
                            Button(
                                onClick = {
                                    runCatching { crashFile.delete() }
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.crash_dismiss))
                            }
                        }
                    }
                }
            }
        }
    }
}
