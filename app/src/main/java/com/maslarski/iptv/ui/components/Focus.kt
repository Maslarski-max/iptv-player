package com.maslarski.iptv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maslarski.iptv.ui.theme.Palette

/**
 * Shared focus visuals: an animated scale, a neon gradient border and a soft outer glow so the
 * focused element is unmistakable from across the room.
 */
@Composable
fun Modifier.focusGlow(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    focusedScale: Float = 1.06f,
    borderWidth: Dp = 3.dp,
    glowColor: Color = Palette.NeonPurple,
    animateScale: Boolean = true,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused && animateScale) focusedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "focusScale",
    )
    val glowAlpha by animateFloatAsState(if (focused) 0.65f else 0f, tween(200), label = "glowAlpha")
    val width by animateDpAsState(if (focused) borderWidth else 0.dp, tween(150), label = "borderWidth")
    val borderBrush = if (focused) Palette.FocusGradient else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    return this
        .scale(scale)
        .drawBehind {
            if (glowAlpha > 0f) {
                val outline = shape.createOutline(size, layoutDirection, this)
                for (i in 1..4) {
                    drawOutline(
                        outline = outline,
                        color = glowColor.copy(alpha = glowAlpha / (i * 1.8f)),
                        style = Stroke(width = (borderWidth * i * 2).toPx()),
                    )
                }
            }
        }
        .border(width, borderBrush, shape)
}

@Composable
fun rememberFocusState(interactionSource: MutableInteractionSource): State<Boolean> =
    interactionSource.collectIsFocusedAsState()

@Composable
fun Modifier.focusHighlightBackground(
    interactionSource: MutableInteractionSource,
    focusedColor: Color = Palette.SurfaceHighest,
    unfocusedColor: Color = Color.Transparent,
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val color by animateColorAsState(if (focused) focusedColor else unfocusedColor, tween(150), label = "focusBg")
    return drawBehind { drawRect(color) }
}

@Composable
fun rememberInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

fun Modifier.dpadFocusable(interactionSource: MutableInteractionSource): Modifier =
    focusable(interactionSource = interactionSource)

/**
 * Lets D-pad Up/Down leave a single-line text field (Compose text fields otherwise swallow them),
 * [down] names the primary action to land on instead of the nearest button. Enter/OK keeps its
 * default behaviour (opens the on-screen keyboard).
 */
@Composable
fun Modifier.dpadTextField(down: FocusRequester? = null): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown ->
                (down != null && runCatching { down.requestFocus(); true }.getOrDefault(false)) ||
                    focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
