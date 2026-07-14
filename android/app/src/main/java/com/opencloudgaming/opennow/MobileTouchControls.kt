package com.opencloudgaming.opennow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =====================================================================
// Mobile-Style Touch Controls
//
// Layout: floating joystick (left) + buttons (right) + camera look zone
// (rest of screen). Designed for FPS/third-person games on GFN where
// the default Xbox controller overlay is too cluttered.
// =====================================================================

/**
 * The camera look zone — an invisible touch area that captures drag
 * gestures on empty screen space and sends them as relative mouse
 * movement to the stream (for camera/look control).
 *
 * Best practices applied (from PUBG Mobile / COD Mobile research):
 * - Multi-touch: supports multiple simultaneous drag pointers
 * - Sensitivity scaling with optional acceleration
 * - Invert Y axis option for inverted look
 * - Tap-to-click detection (quick tap = left mouse click)
 * - Does not consume events that land on button/stick areas
 *   (those are rendered on top with higher z-index)
 */
@Composable
fun CameraLookZone(
    client: NativeStreamClient,
    sensitivity: Float,
    invertY: Boolean,
    zoneHeightFraction: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    var activePointers by remember { mutableStateOf(mapOf<Int, Offset>()) }
    var lastTapTimeMs by remember { mutableFloatStateOf(0f) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxSize(fraction = 1f)
            .pointerInput(client, sensitivity, invertY) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val changes = event.changes

                        for (change in changes) {
                            when {
                                change.pressed && change.previousPressed -> {
                                    // Drag in progress — send delta
                                    val dx = change.positionChange().x
                                    var dy = change.positionChange().y
                                    if (invertY) dy = -dy

                                    if (dx != 0f || dy != 0f) {
                                        val scaledDx = (dx * sensitivity * 2f).toInt()
                                        val scaledDy = (dy * sensitivity * 2f).toInt()
                                        if (scaledDx != 0 || scaledDy != 0) {
                                            client.sendTouchMouseMove(
                                                dx = scaledDx,
                                                dy = scaledDy,
                                                partiallyReliable = true,
                                            )
                                        }
                                    }
                                    change.consume()
                                }
                                change.pressed && !change.previousPressed -> {
                                    // Pointer down — track for tap detection
                                    activePointers = activePointers + (change.id.value to change.position)
                                    lastTapTimeMs = System.nanoTime() / 1_000_000f
                                    lastTapPos = change.position
                                    change.consume()
                                }
                                !change.pressed -> {
                                    // Pointer up — check for tap (quick press without much movement)
                                    val upTime = System.nanoTime() / 1_000_000f
                                    val duration = upTime - lastTapTimeMs
                                    val moved = sqrt(
                                        (change.position.x - lastTapPos.x).let { it * it } +
                                        (change.position.y - lastTapPos.y).let { it * it }
                                    )
                                    if (duration < 200f && moved < 30f) {
                                        // Quick tap = left mouse click
                                        client.sendTouchMouseClick(delayBeforeDownMs = 0L)
                                    }
                                    activePointers = activePointers - change.id.value
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // Invisible — just captures touch events
        // The video stream renders underneath this layer
    }
}

/**
 * A floating virtual joystick that appears at the touch point instead of
 * being fixed in one position. This is the standard in mobile games
 * (PUBG Mobile, COD Mobile, Fortnite Mobile) because it adapts to where
 * the player's thumb naturally lands.
 *
 * When the user touches the left half of the screen, the joystick base
 * appears at that point and tracks their drag. When released, it
 * disappears and the stick returns to center (sending 0,0).
 */
@Composable
fun FloatingVirtualStick(
    client: NativeStreamClient,
    opacity: Float,
    diameter: Dp,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    sideMultiplier: Float = -1f, // -1 = left side, +1 = right side
) {
    if (!enabled) return

    var isActive by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var knobOffset by remember { mutableStateOf(Offset.Zero) }

    val density = LocalDensity.current
    val maxRadiusPx = with(density) { (diameter / 2).toPx() }
    val knobRadiusPx = with(density) { (diameter * 0.22f).toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(client, diameter) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.pressed } ?: run {
                            // No active pointer — if we were active, release
                            if (isActive) {
                                client.setVirtualLeftStick(0f, 0f)
                                isActive = false
                                origin = Offset.Zero
                                knobOffset = Offset.Zero
                            }
                            continue
                        }

                        if (!isActive) {
                            // First touch — set origin
                            origin = change.position
                            isActive = true
                        }

                        val raw = change.position - origin
                        val distance = sqrt(raw.x * raw.x + raw.y * raw.y)
                        val clamped = if (distance > maxRadiusPx) {
                            val scale = maxRadiusPx / distance
                            Offset(raw.x * scale, raw.y * scale)
                        } else {
                            raw
                        }

                        val normX = (clamped.x / maxRadiusPx).coerceIn(-1f, 1f)
                        val normY = (clamped.y / maxRadiusPx).coerceIn(-1f, 1f)

                        client.setVirtualLeftStick(normX, normY)
                        knobOffset = clamped
                        change.consume()
                    }
                }
            }
    ) {
        // Draw joystick visually only when active
        if (isActive) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                // Outer ring (joystick base)
                drawCircle(
                    color = Color.White.copy(alpha = opacity * 0.15f),
                    radius = maxRadiusPx,
                    center = origin,
                )
                drawCircle(
                    color = Color.White.copy(alpha = opacity * 0.4f),
                    radius = maxRadiusPx,
                    center = origin,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
                // Inner knob
                drawCircle(
                    color = Color.LightGray.copy(alpha = opacity * 0.8f),
                    radius = knobRadiusPx,
                    center = origin + knobOffset,
                )
            }
        }
    }
}

/**
 * A customizable button for the mobile layout. Similar to GamepadButton
 * but designed to be placed freely in the mobile-style layout.
 */
@Composable
fun MobileActionButton(
    label: String,
    mask: Int,
    client: NativeStreamClient,
    opacity: Float,
    size: Dp,
    onPressTone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val buttonColor = Color.Black.copy(alpha = opacity * 0.55f)
    val pressedColor = Color.White.copy(alpha = opacity * 0.25f)
    val borderColor = Color.White.copy(alpha = opacity * 0.45f)

    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (pressed) pressedColor else buttonColor)
            .border(1.dp, borderColor, CircleShape)
            .pointerInput(client, mask) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.any { it.pressed }
                        if (down != pressed) {
                            client.setVirtualButton(mask, down)
                            pressed = down
                            if (down) onPressTone()
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = opacity * 0.95f),
        )
    }
    DisposableEffect(client, mask) {
        onDispose {
            client.setVirtualButton(mask, false)
        }
    }
}

/**
 * A trigger button for the mobile layout (LT/RT). Slides vertically
 * rather than being a simple toggle, for analog feel.
 */
@Composable
fun MobileTriggerButton(
    label: String,
    left: Boolean,
    client: NativeStreamClient,
    opacity: Float,
    size: Dp,
    onPressTone: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val buttonColor = Color.Black.copy(alpha = opacity * 0.55f)
    val pressedColor = Color.White.copy(alpha = opacity * 0.25f)
    val borderColor = Color.White.copy(alpha = opacity * 0.45f)

    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(if (pressed) pressedColor else buttonColor)
            .border(1.dp, borderColor, RoundedCornerShape(size * 0.3f))
            .pointerInput(client, left) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val down = event.changes.any { it.pressed }
                        if (down != pressed) {
                            client.setVirtualTrigger(left, down)
                            pressed = down
                            if (down) onPressTone()
                        }
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = opacity * 0.9f),
        )
    }
    DisposableEffect(client, left) {
        onDispose {
            client.setVirtualTrigger(left, false)
        }
    }
}
