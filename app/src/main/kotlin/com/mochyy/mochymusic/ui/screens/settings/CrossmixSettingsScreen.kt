package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.CrossfadeDurationKey
import com.mochyy.mochymusic.constants.CrossfadeEnabledKey
import com.mochyy.mochymusic.constants.CrossfadeGaplessKey
import com.mochyy.mochymusic.constants.CrossmixMode
import com.mochyy.mochymusic.constants.CrossmixModeKey
import com.mochyy.mochymusic.constants.DefaultTransitionMode
import com.mochyy.mochymusic.ui.component.SubSettingsScaffold
import com.mochyy.mochymusic.ui.screens.audiolab.LabCard
import com.mochyy.mochymusic.ui.screens.audiolab.LabCollapsible
import com.mochyy.mochymusic.ui.screens.audiolab.LabColors
import com.mochyy.mochymusic.ui.screens.audiolab.LabSlider
import com.mochyy.mochymusic.ui.screens.audiolab.LabToggleRow
import com.mochyy.mochymusic.ui.theme.InterFontFamily
import com.mochyy.mochymusic.utils.rememberEnumPreference
import com.mochyy.mochymusic.utils.rememberPreference
import kotlin.math.roundToInt

private const val DefaultCrossfadeSeconds = 5f
private const val CrossfadeMinSeconds = 1f
private const val CrossfadeMaxSeconds = 12f
private val HeadingColor = Color.White.copy(alpha = 0.82f)

@Composable
fun CrossmixSettingsScreen(navController: NavController) {
    val (storedMode, onStoredModeChange) = rememberEnumPreference(
        CrossmixModeKey,
        defaultValue = DefaultTransitionMode,
    )
    val (crossfadeEnabled, onCrossfadeEnabledChange) = rememberPreference(
        CrossfadeEnabledKey,
        defaultValue = false,
    )
    val (crossfadeDuration, onCrossfadeDurationChange) = rememberPreference(
        CrossfadeDurationKey,
        defaultValue = DefaultCrossfadeSeconds,
    )
    val (disableForGapless, onDisableForGaplessChange) = rememberPreference(
        CrossfadeGaplessKey,
        defaultValue = true,
    )

    val isEnabled = crossfadeEnabled && storedMode != CrossmixMode.OFF
    val durationSeconds = crossfadeDuration.coerceIn(CrossfadeMinSeconds, CrossfadeMaxSeconds)

    fun setCrossfadeState(enabled: Boolean) {
        onCrossfadeEnabledChange(enabled)
        onStoredModeChange(if (enabled) CrossmixMode.MANUAL_CROSSFADE else CrossmixMode.OFF)
    }

    SubSettingsScaffold(
        title = "Crossfade",
        backLabel = stringResource(R.string.xmix_back),
        onBack = { navController.navigateUp() },
    ) {
        TransitionHeading("Track Transition")
        LabCard(padding = 10.dp) {
            LabToggleRow(
                title = "Smooth Crossfade",
                subtitle = "Seamlessly blend into the next track like Morphe / Spotify without gaps or tempo distortion",
                checked = isEnabled,
                onCheckedChange = { setCrossfadeState(it) },
            )
        }
        Spacer(Modifier.height(12.dp))

        LabCollapsible(isEnabled) {
            Column {
                LabCard(padding = 14.dp) {
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        LabSlider(
                            label = "Crossfade Length",
                            valueText = "${durationSeconds.roundToInt()}s",
                            value = durationSeconds,
                            onValueChange = { onCrossfadeDurationChange(it.roundToInt().toFloat()) },
                            valueRange = CrossfadeMinSeconds..CrossfadeMaxSeconds,
                            steps = (CrossfadeMaxSeconds - CrossfadeMinSeconds).toInt() - 1,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "${CrossfadeMinSeconds.toInt()}s",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = LabColors.Secondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${CrossfadeMaxSeconds.toInt()}s",
                                fontFamily = InterFontFamily,
                                fontSize = 11.sp,
                                color = LabColors.Secondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                LabCard(padding = 10.dp) {
                    LabToggleRow(
                        title = "Bypass for Gapless Albums",
                        subtitle = "Keep original continuous playback for tracks from the same album",
                        checked = disableForGapless,
                        onCheckedChange = onDisableForGaplessChange,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        TransportFadeSection()
        Spacer(Modifier.height(120.dp))
    }
}

/** the soft heading over each group on the transition page */
@Composable
internal fun TransitionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.2).sp,
        color = HeadingColor,
        modifier = modifier.padding(start = 4.dp, end = 16.dp, bottom = 10.dp),
    )
}
