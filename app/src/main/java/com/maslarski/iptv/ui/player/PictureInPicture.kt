package com.maslarski.iptv.ui.player

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

internal object PlayerWindowOwners {
    var immersiveCount = 0
    var pipCount = 0
}

@Stable
class PipHandle(val supported: Boolean, inPip: State<Boolean>, private val enter: () -> Boolean) {
    val inPip by inPip
    fun enter(): Boolean = enter.invoke()
}

@Composable
fun rememberPictureInPicture(isPlaying: Boolean, aspect: Rational?): PipHandle {
    val activity = LocalActivity.current as? ComponentActivity
    val supported = remember(activity) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    }
    val inPipState = remember(activity) { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val latestIsPlaying = rememberUpdatedState(isPlaying)
    val latestAspect = rememberUpdatedState(aspect)
    val enter = remember(activity, supported, latestAspect) {
        {
            if (supported && activity != null) {
                runCatching {
                    activity.enterPictureInPictureMode(pictureInPictureParams(latestAspect.value, autoEnter = true))
                }.getOrDefault(false)
            } else {
                false
            }
        }
    }
    val latestEnter = rememberUpdatedState(enter)

    LaunchedEffect(isPlaying, aspect) {
        if (supported && activity != null) {
            activity.setPictureInPictureParams(pictureInPictureParams(aspect, autoEnter = isPlaying))
        }
    }

    DisposableEffect(activity) {
        if (activity == null || !supported) {
            onDispose {}
        } else {
            PlayerWindowOwners.pipCount++
            val userLeaveHintListener = Runnable {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && latestIsPlaying.value) latestEnter.value()
            }
            val pictureInPictureModeChangedListener = Consumer<PictureInPictureModeChangedInfo> {
                inPipState.value = it.isInPictureInPictureMode
            }
            activity.addOnUserLeaveHintListener(userLeaveHintListener)
            activity.addOnPictureInPictureModeChangedListener(pictureInPictureModeChangedListener)
            inPipState.value = activity.isInPictureInPictureMode
            onDispose {
                activity.removeOnUserLeaveHintListener(userLeaveHintListener)
                activity.removeOnPictureInPictureModeChangedListener(pictureInPictureModeChangedListener)
                PlayerWindowOwners.pipCount--
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && PlayerWindowOwners.pipCount == 0) {
                    activity.setPictureInPictureParams(pictureInPictureParams(latestAspect.value, autoEnter = false))
                }
            }
        }
    }

    return remember(activity, supported, inPipState, latestEnter) {
        PipHandle(supported, inPipState) { latestEnter.value() }
    }
}

private fun pictureInPictureParams(aspect: Rational?, autoEnter: Boolean): PictureInPictureParams {
    val ratio = aspect ?: Rational(16, 9)
    val minRatio = Rational(100, 239)
    val maxRatio = Rational(239, 100)
    val clampedRatio = when {
        ratio < minRatio -> minRatio
        ratio > maxRatio -> maxRatio
        else -> ratio
    }
    return PictureInPictureParams.Builder()
        .setAspectRatio(clampedRatio)
        .apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setAutoEnterEnabled(autoEnter)
                setSeamlessResizeEnabled(true)
            }
        }
        .build()
}
