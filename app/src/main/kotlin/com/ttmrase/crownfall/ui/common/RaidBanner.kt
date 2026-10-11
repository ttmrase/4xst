package com.ttmrase.crownfall.ui.common

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.RaidStage
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.util.DurationFormat

private val RAID_PATTERN = longArrayOf(0, 500, 150, 500, 150, 800)
private const val OFFER_TICKER_SEC = 3_600L

/**
 * 緊急ティッカー（全画面の上部）。襲撃の段階が出ているあいだは赤い警報バナー（段階名と残り時間）、
 * 段階が進んだ瞬間には強めのバイブ（端末のバイブ設定に従う）。オファーの終了が 1 時間以内なら、その残り時間も出す。
 */
@Composable
fun RaidBanner(
    session: GameSession,
    data: GameData,
) {
    val context = LocalContext.current
    LaunchedEffect(session) {
        session.events.collect { e ->
            if (e is GameEvent.RaidStageReached && e.stage != RaidStage.RESULT) {
                Sfx.play(context, SfxId.ALARM)
                vibrate(context)
            }
        }
    }
    val state by session.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val raidAt = s.map.nextRaidEpochSec
    val left = remainingUntil(raidAt, s.nowEpochSec)
    val stageLabel = currentStageLabel(s.map.raidStageDone, Marching.levelOf(s, BuildingType.WATCHTOWER), data)
    Column {
        if (left > 0 && (stageLabel != null || s.map.raidWarned)) {
            Surface(color = Color(RED), modifier = Modifier.fillMaxWidth()) {
                val text =
                    if (stageLabel != null) {
                        val label = stringResource(stageLabel)
                        stringResource(R.string.raid_stage_banner, label, DurationFormat.clock(left))
                    } else {
                        stringResource(R.string.raid_banner, DurationFormat.clock(left))
                    }
                Text(
                    text,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        val offer = OfferEngine.active(s).minByOrNull { it.expiresEpochSec }
        val offerLeft = offer?.let { remainingUntil(it.expiresEpochSec, s.nowEpochSec) } ?: 0L
        if (offer != null && offerLeft in 1..OFFER_TICKER_SEC) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.ticker_offer, DurationFormat.clock(offerLeft)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** 今の襲撃で最後に出た段階の名前（物見の塔の Lv が足りない段階は見えないので null）。 */
private fun currentStageLabel(
    done: Int,
    watchtower: Int,
    data: GameData,
): Int? {
    val rule = data.notifyRules.raid.stages.getOrNull(done - 1) ?: return null
    if (watchtower < rule.minWatchtowerLv) return null
    return when (rule.stage) {
        RaidStage.SCOUTED.name -> R.string.raid_stage_scouted
        RaidStage.MARCHING.name -> R.string.raid_stage_marching
        RaidStage.T_MINUS_1H.name -> R.string.raid_stage_1h
        RaidStage.T_MINUS_15M.name -> R.string.raid_stage_15m
        RaidStage.STARTED.name -> R.string.raid_stage_started
        else -> null
    }
}

private fun vibrate(context: Context) {
    val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    if (vibrator?.hasVibrator() != true) return
    // マナーモード（無音）では鳴らさない。通知用途として振動させるので、おやすみモードの設定も効く
    val ringer = context.getSystemService(AudioManager::class.java)?.ringerMode
    if (ringer == AudioManager.RINGER_MODE_SILENT) return
    val effect = VibrationEffect.createWaveform(RAID_PATTERN, -1)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
    } else {
        vibrator.vibrate(effect)
    }
}

private const val RED = 0xFFC62828
