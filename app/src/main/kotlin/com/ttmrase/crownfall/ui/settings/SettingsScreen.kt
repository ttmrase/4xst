package com.ttmrase.crownfall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ai.AiService
import com.ttmrase.crownfall.ai.AiSettings
import com.ttmrase.crownfall.ai.OnDeviceProvider
import com.ttmrase.crownfall.ai.providers.GeminiProvider
import com.ttmrase.crownfall.ai.providers.UrlPolicy
import com.ttmrase.crownfall.core.chat.RevealAi
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.feature.LockedFeatureCard
import kotlinx.coroutines.launch

/**
 * 設定画面（T5.5）。AI プロバイダの順序と有効 / 無効、API キー（マスク入力）、ベース URL、モデル名、
 * 呼び出し予算、直近 24h の使用状況。キーが無くても Scripted だけで遊べる。
 */
@Composable
fun SettingsScreen(
    ai: AiService,
    session: GameSession?,
    snackbarHostState: SnackbarHostState,
    data: GameData? = null,
) {
    val loaded by ai.settings.collectAsStateWithLifecycle()
    val base = loaded
    if (base == null) {
        Text(stringResource(R.string.settings_loading), modifier = Modifier.padding(16.dp))
        return
    }
    SettingsForm(ai, base, session, snackbarHostState, data)
}

@Composable
private fun SettingsForm(
    ai: AiService,
    base: AiSettings,
    session: GameSession?,
    snackbarHostState: SnackbarHostState,
    data: GameData?,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(base) }
    var stats by remember { mutableStateOf(ai.usageStats()) }
    var gpuFailed by remember { mutableStateOf(ai.onDeviceGpuFailed()) }
    val saved = stringResource(R.string.settings_saved)
    val badUrl = stringResource(R.string.settings_url_rejected)
    val saveFailed = stringResource(R.string.settings_save_failed)
    val urlProblem = draft.openAiBaseUrl.isNotBlank() && UrlPolicy.reject(draft.openAiBaseUrl.trim()) != null
    // 段階解放: AI の設定は同盟（城 Lv4）、ショップの設定はショップ（城 Lv3）の解放まで灰色にする
    val gameState by (session?.collectCalmState() ?: remember { mutableStateOf<GameState?>(null) })
    val aiLocked = data != null && gameState != null && !FeatureGate.unlocked(gameState!!, data, Feature.ALLIANCE)
    val shopLocked = data != null && gameState != null && !FeatureGate.unlocked(gameState!!, data, Feature.SHOP)

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (aiLocked && data != null) {
            LockedFeatureCard(Feature.ALLIANCE, data)
        } else {
            Text(stringResource(R.string.settings_ai_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_ai_note), style = MaterialTheme.typography.bodySmall)

            Text(stringResource(R.string.settings_order), style = MaterialTheme.typography.titleSmall)
            draft.order.forEachIndexed { i, id ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_order_row, i + 1, providerLabel(id)),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = i > 0,
                            onClick = { draft = draft.copy(order = move(draft.order, i, i - 1)) },
                        ) { Text("↑") }
                        OutlinedButton(
                            enabled = i < draft.order.lastIndex,
                            onClick = { draft = draft.copy(order = move(draft.order, i, i + 1)) },
                        ) { Text("↓") }
                        Switch(
                            checked = id !in draft.disabled,
                            onCheckedChange = { on ->
                                draft = draft.copy(disabled = if (on) draft.disabled - id else draft.disabled + id)
                            },
                        )
                    }
                }
            }
            Text(stringResource(R.string.settings_scripted_last), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()

            Text(stringResource(R.string.settings_gemini), style = MaterialTheme.typography.titleSmall)
            SecretField(R.string.settings_api_key, draft.geminiKey) { draft = draft.copy(geminiKey = it) }
            OutlinedTextField(
                value = draft.geminiModel,
                onValueChange = { draft = draft.copy(geminiModel = it) },
                label = { Text(stringResource(R.string.settings_model)) },
                supportingText = {
                    Text(
                        stringResource(R.string.settings_gemini_model_hint, GeminiProvider.DEFAULT_MODEL),
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider()

            Text(stringResource(R.string.settings_openai), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = draft.openAiBaseUrl,
                onValueChange = { draft = draft.copy(openAiBaseUrl = it) },
                label = { Text(stringResource(R.string.settings_base_url)) },
                isError = urlProblem,
                supportingText = { Text(if (urlProblem) badUrl else stringResource(R.string.settings_base_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.openAiModel,
                onValueChange = { draft = draft.copy(openAiModel = it) },
                label = { Text(stringResource(R.string.settings_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(R.string.settings_api_key_optional, draft.openAiKey) { draft = draft.copy(openAiKey = it) }
            HorizontalDivider()

            OnDeviceSettingsSection(
                store = ai.modelStore,
                chargingOnly = draft.onDeviceChargingOnly,
                timeoutSec = draft.onDeviceTimeoutSec,
                onChargingOnly = { draft = draft.copy(onDeviceChargingOnly = it) },
                onTimeoutSec = { draft = draft.copy(onDeviceTimeoutSec = it) },
                profile = draft.onDeviceProfile,
                backend = draft.onDeviceBackend,
                onProfile = { draft = draft.copy(onDeviceProfile = it) },
                onBackend = { draft = draft.copy(onDeviceBackend = it) },
                gpuFailed = gpuFailed,
                onRetryGpu = {
                    ai.retryOnDeviceGpu()
                    gpuFailed = false
                },
            )
            OnDeviceDebugSection(ai, data)
            HorizontalDivider()

            Text(stringResource(R.string.settings_budget), style = MaterialTheme.typography.titleSmall)
            NumberField(R.string.settings_budget_hourly, draft.hourlyBudget) { draft = draft.copy(hourlyBudget = it) }
            NumberField(R.string.settings_budget_daily, draft.dailyBudget) { draft = draft.copy(dailyBudget = it) }
            HorizontalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_reveal_ai))
                    Text(stringResource(R.string.settings_reveal_ai_note), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = draft.revealAi == RevealAi.ADMIT,
                    onCheckedChange = { draft = draft.copy(revealAi = if (it) RevealAi.ADMIT else RevealAi.AMBIGUOUS) },
                )
            }
            HorizontalDivider()

            Text(stringResource(R.string.settings_usage), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_usage_counts, stats.requests, stats.failures))
            OutlinedButton(
                onClick = { stats = ai.usageStats() },
            ) { Text(stringResource(R.string.settings_usage_refresh)) }

            Button(
                enabled = !urlProblem,
                onClick = {
                    scope.launch {
                        val ok = draft.keysValid() && ai.settingsStore.update { draft }
                        stats = ai.usageStats()
                        snackbarHostState.showReplacing(if (ok) saved else saveFailed)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_save)) }
        }
        if (session != null) {
            HorizontalDivider()
            AvatarSettingsSection(session)
            if (data != null) TitleSettingsSection(session, data)
        }
        HorizontalDivider()
        MapSettingsSection()
        HorizontalDivider()
        SoundSettingsSection()
        HorizontalDivider()
        NotificationSettingsSection(session)
        if (session != null) {
            HorizontalDivider()
            if (shopLocked && data != null) LockedFeatureCard(Feature.SHOP, data) else ShopSettingsSection(session)
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_no_real_billing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun providerLabel(id: String): String =
    when (id) {
        GeminiProvider.ID -> stringResource(R.string.settings_gemini)
        OnDeviceProvider.ID -> stringResource(R.string.settings_on_device)
        else -> stringResource(R.string.settings_openai)
    }

private fun move(
    list: List<String>,
    from: Int,
    to: Int,
): List<String> = list.toMutableList().also { it.add(to, it.removeAt(from)) }

/** API キーなどの秘密の入力欄（常にマスク表示）。 */
@Composable
private fun SecretField(
    labelRes: Int,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelRes)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun NumberField(
    labelRes: Int,
    value: Int,
    onChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = {
                text ->
            text.filter { it.isDigit() }.take(MAX_DIGITS).toIntOrNull()?.let(onChange) ?: onChange(0)
        },
        label = { Text(stringResource(labelRes)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MAX_DIGITS = 6
