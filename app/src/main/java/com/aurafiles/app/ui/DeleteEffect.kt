package com.aurafiles.app.ui

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.aurafiles.app.model.DeleteAnimationMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

internal const val DELETE_DISSOLVE_DURATION_MS = 360
internal const val DELETE_SAND_DURATION_MS = 720
internal const val DELETE_BURN_DURATION_MS = 1450
internal const val DELETE_DISSOLVE_MAX_STAGGER_MS = 70
internal const val DELETE_SAND_MAX_STAGGER_MS = 55
internal const val DELETE_BURN_MAX_STAGGER_MS = 45

internal fun DeleteAnimationMode.preDeleteDelayMillis(): Long = when (this) {
    DeleteAnimationMode.Off -> 0L
    DeleteAnimationMode.Dissolve -> (DELETE_DISSOLVE_DURATION_MS + DELETE_DISSOLVE_MAX_STAGGER_MS).toLong()
    // Smoke is kept as the persisted enum value for compatibility with 0.14.15/0.14.16.
    // In the UI this mode is now the sand/grain wind effect.
    DeleteAnimationMode.Smoke -> (DELETE_SAND_DURATION_MS + DELETE_SAND_MAX_STAGGER_MS).toLong()
    DeleteAnimationMode.Burn -> (DELETE_BURN_DURATION_MS + DELETE_BURN_MAX_STAGGER_MS).toLong()
}

/**
 * Lightweight deletion animations that avoid capturing the deleted item into a bitmap.
 *
 * Dissolve redraws the real composable through a deterministic mosaic mask.
 * The legacy Smoke mode is intentionally kept as the stored enum name, but visually it now
 * behaves like fine grains being blown off a sheet: a noisy erosion front travels across the
 * item, tiny cells disappear from the original surface, and lightweight particles fly away.
 * Burn is intentionally more physical: ignition starts along the lower edge, spreads sideways,
 * chars the material, then advances upward with a ragged front while the brightest embers are
 * confined to intermittent pockets rather than a continuous neon outline.
 */
@Composable
internal fun Modifier.auraDeleteEffect(
    active: Boolean,
    mode: DeleteAnimationMode,
    seed: Int = 0,
): Modifier {
    if (mode == DeleteAnimationMode.Off) return this

    val maxStagger = when (mode) {
        DeleteAnimationMode.Dissolve -> DELETE_DISSOLVE_MAX_STAGGER_MS
        DeleteAnimationMode.Smoke -> DELETE_SAND_MAX_STAGGER_MS
        DeleteAnimationMode.Burn -> DELETE_BURN_MAX_STAGGER_MS
        else -> 0
    }
    val delay = if (active && maxStagger > 0) {
        (seed and Int.MAX_VALUE) % (maxStagger + 1)
    } else 0

    val duration = when (mode) {
        DeleteAnimationMode.Off -> 0
        DeleteAnimationMode.Dissolve -> DELETE_DISSOLVE_DURATION_MS
        DeleteAnimationMode.Smoke -> DELETE_SAND_DURATION_MS
        DeleteAnimationMode.Burn -> DELETE_BURN_DURATION_MS
    }
    val progress by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = duration, delayMillis = delay),
        label = "aura-delete-effect",
    )

    if (mode == DeleteAnimationMode.Smoke) {
        val grainColor = MaterialTheme.colorScheme.onSurface
        val accentGrainColor = MaterialTheme.colorScheme.primary
        return this
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = (1f - progress * 0.04f).coerceIn(0f, 1f)
            }
            .drawWithContent {
                if (progress <= 0.001f) {
                    drawContent()
                    return@drawWithContent
                }
                if (progress >= 0.998f || size.width <= 0f || size.height <= 0f) return@drawWithContent

                drawContent()

                val p = smoothStep(progress.coerceIn(0f, 1f))
                val aspect = (size.width / size.height.coerceAtLeast(1f)).coerceIn(0.2f, 8f)
                val targetCells = when {
                    aspect >= 3.0f -> 360f
                    aspect >= 1.4f -> 480f
                    aspect >= 0.8f -> 650f
                    else -> 900f
                }
                val columns = sqrt(targetCells * aspect).toInt().coerceIn(14, 48)
                val rows = ceil(targetCells / columns.toFloat()).toInt().coerceIn(7, 48)
                val cellWidth = size.width / columns.toFloat()
                val cellHeight = size.height / rows.toFloat()

                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val n1 = sandNoise(seed, row, column, 0)
                        val n2 = sandNoise(seed, row, column, 1)
                        val n3 = sandNoise(seed, row, column, 2)

                        val xNorm = (column + 0.5f) / columns.toFloat()
                        val yNorm = (row + 0.5f) / rows.toFloat()
                        val heightFromBottom = 1f - yNorm
                        val front = sandFrontHeightNormalized(
                            xNorm = xNorm,
                            progress = p,
                            seed = seed,
                            sampleIndex = row * 53 + column,
                        )
                        val coverage = front - heightFromBottom
                        if (coverage <= -0.03f) continue

                        val local = smoothStep(((coverage + 0.05f) / 0.17f).coerceIn(0f, 1f))
                        val left = column * cellWidth
                        val top = row * cellHeight
                        val right = if (column == columns - 1) size.width else left + cellWidth + 0.6f
                        val bottom = if (row == rows - 1) size.height else top + cellHeight + 0.6f

                        val clearScale = (0.18f + 0.82f * smoothStep(local)).coerceIn(0.18f, 1f)
                        val cellW = (right - left).coerceAtLeast(1f)
                        val cellH = (bottom - top).coerceAtLeast(1f)
                        val holeW = cellW * clearScale
                        val holeH = cellH * clearScale
                        val jitterX = (n2 - 0.5f) * cellW * 0.34f * (1f - clearScale)
                        val jitterY = (n3 - 0.5f) * cellH * 0.28f * (1f - clearScale)
                        drawRect(
                            color = androidx.compose.ui.graphics.Color.Transparent,
                            topLeft = Offset(
                                left + (cellW - holeW) * 0.5f + jitterX,
                                top + (cellH - holeH) * 0.5f + jitterY,
                            ),
                            size = Size(holeW.coerceAtLeast(1f), holeH.coerceAtLeast(1f)),
                            blendMode = BlendMode.Clear,
                        )

                        if (local < 0.99f) {
                            val eased = local * local
                            val rise = size.height * (0.07f + 0.24f * n2) * local + size.height * 0.16f * eased
                            val sideDrift = size.width * (n3 - 0.5f) * 0.12f * local
                            val flutter = (n1 - 0.5f) * size.width * 0.035f * local
                            val cx = left + cellWidth * (0.22f + 0.56f * n2) + sideDrift + flutter
                            val cy = top + cellHeight * (0.42f + 0.42f * n3) - rise
                            val base = minOf(cellWidth, cellHeight)
                            val radius = (base * (0.09f + 0.12f * n1) * (1f - local * 0.54f)).coerceAtLeast(0.65f)
                            val alpha = ((1f - local) * (0.46f + 0.36f * n2)).coerceIn(0f, 0.82f)
                            val color = if (n3 > 0.84f) accentGrainColor else grainColor
                            drawCircle(
                                color = color.copy(alpha = alpha),
                                radius = radius,
                                center = Offset(cx, cy),
                            )

                            if (n2 > 0.54f) {
                                val secondLocal = ((local - 0.04f) / 0.96f).coerceIn(0f, 1f)
                                val cx2 = cx + cellWidth * (n1 - 0.5f) * 0.85f + size.width * 0.028f * secondLocal
                                val cy2 = cy - cellHeight * (0.18f + n3 * 0.65f) * secondLocal
                                drawCircle(
                                    color = grainColor.copy(alpha = alpha * 0.50f),
                                    radius = (radius * 0.58f).coerceAtLeast(0.45f),
                                    center = Offset(cx2, cy2),
                                )
                            }
                        }
                    }
                }
            }
    }
    if (mode == DeleteAnimationMode.Burn) {
        if (!active && progress <= 0.001f) return this
        return if (Build.VERSION.SDK_INT >= 33) {
            this.auraBurnRuntimeShaderEffect(progress = progress, seed = seed)
        } else {
            this.auraBurnFallbackEffect(progress = progress, seed = seed)
        }
    }

    return this
        .graphicsLayer {
            alpha = 1f - progress * 0.12f
            translationY = progress * 4f
        }
        .drawWithContent {
            if (progress <= 0.001f) {
                drawContent()
                return@drawWithContent
            }
            if (progress >= 0.995f || size.width <= 0f || size.height <= 0f) return@drawWithContent

            val columns = 12
            val cellWidth = size.width / columns.toFloat()
            val rows = ceil(size.height / cellWidth.coerceAtLeast(1f)).toInt().coerceIn(1, 24)
            val cellHeight = size.height / rows.toFloat()
            val shrink = progress * 0.16f

            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val threshold = dissolveNoise(seed, row, column)
                    if (threshold <= progress) continue
                    val left = column * cellWidth
                    val top = row * cellHeight
                    val right = if (column == columns - 1) size.width else left + cellWidth
                    val bottom = if (row == rows - 1) size.height else top + cellHeight
                    val insetX = (right - left) * shrink
                    val insetY = (bottom - top) * shrink
                    clipRect(
                        left = left + insetX,
                        top = top + insetY,
                        right = right - insetX,
                        bottom = bottom - insetY,
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
            }
        }
}

@RequiresApi(33)
@Composable
private fun Modifier.auraBurnRuntimeShaderEffect(
    progress: Float,
    seed: Int,
): Modifier {
    var contentSize by remember { mutableStateOf(IntSize(0, 0)) }
    val shader = remember(seed) {
        runCatching { RuntimeShader(BURN_SHADER_SOURCE) }.getOrNull()
    } ?: return auraBurnFallbackEffect(progress = progress, seed = seed)

    val effect = if (contentSize.width > 0 && contentSize.height > 0) {
        remember(shader, progress, contentSize) {
            runCatching {
                shader.setFloatUniform(
                    "resolution",
                    contentSize.width.toFloat(),
                    contentSize.height.toFloat(),
                )
                shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
                shader.setFloatUniform("seed", ((seed and 0xFFFF) / 65535f))
                AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
            }.getOrNull()
        }
    } else null

    if (effect == null) {
        return this
            .onSizeChanged { contentSize = it }
            .auraBurnFallbackEffect(progress = progress, seed = seed)
    }

    return this
        .drawWithContent {
            drawContent()
            drawBurnParticles(progress = progress, seed = seed)
        }
        .onSizeChanged { contentSize = it }
        .graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            renderEffect = effect
        }
}

private fun Modifier.auraBurnFallbackEffect(
    progress: Float,
    seed: Int,
): Modifier {
    val scorchColor = androidx.compose.ui.graphics.Color(0xFF5A3620)
    val charColor = androidx.compose.ui.graphics.Color(0xFF170C09)
    val emberRed = androidx.compose.ui.graphics.Color(0xFF8B1604)
    val emberOrange = androidx.compose.ui.graphics.Color(0xFFD94508)
    val emberYellow = androidx.compose.ui.graphics.Color(0xFFFFC34C)
    val smokeColor = androidx.compose.ui.graphics.Color(0xFF8F8A86)

    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (progress <= 0.001f) {
                drawContent()
                return@drawWithContent
            }
            if (progress >= 0.999f || size.width <= 0f || size.height <= 0f) return@drawWithContent

            drawContent()

            val p = progress.coerceIn(0f, 1f)
            val burn = burnFrontAdvance(p)
            val minDim = minOf(size.width, size.height).coerceAtLeast(1f)
            val aspect = size.width / size.height.coerceAtLeast(1f)
            val samples = if (aspect > 3f) 40 else 60
            val frontPoints = Array(samples + 1) { Offset.Zero }
            val frontPath = Path().apply {
                for (i in 0..samples) {
                    val xNorm = i / samples.toFloat()
                    val frontNorm = burnFrontHeightNormalized(xNorm = xNorm, progress = p, seed = seed, sampleIndex = i)
                    val x = size.width * xNorm
                    val y = size.height * (1f - frontNorm).coerceIn(-0.08f, 1.08f)
                    frontPoints[i] = Offset(x, y)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            if (p > 0.10f) {
                val clearPath = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width, size.height)
                    for (i in samples downTo 0) {
                        val point = frontPoints[i]
                        lineTo(point.x, point.y)
                    }
                    close()
                }
                drawPath(clearPath, androidx.compose.ui.graphics.Color.Transparent, blendMode = BlendMode.Clear)
            }

            val edgeLife = 1f - smoothStep(((burn - 0.98f) / 0.02f).coerceIn(0f, 1f))
            val preheat = smoothStep((p / 0.18f).coerceIn(0f, 1f)) * (1f - smoothStep(((p - 0.22f) / 0.12f).coerceIn(0f, 1f)))
            val scorchWidth = (minDim * 0.036f).coerceIn(2.2f, 22f)
            val charWidth = (minDim * 0.022f).coerceIn(1.8f, 15f)
            drawPath(
                path = frontPath,
                color = scorchColor.copy(alpha = (0.22f + 0.22f * preheat) * edgeLife),
                style = Stroke(width = scorchWidth),
            )
            drawPath(
                path = frontPath,
                color = charColor.copy(alpha = (0.78f * edgeLife).coerceIn(0f, 0.82f)),
                style = Stroke(width = charWidth),
            )

            for (i in 1 until samples) {
                val point = frontPoints[i]
                val prev = frontPoints[i - 1]
                val xNorm = i / samples.toFloat()
                val heat = burnHotPocketMask(xNorm = xNorm, progress = p, seed = seed, sampleIndex = i)
                if (heat <= 0.025f) continue
                val dx = point.x - prev.x
                val dy = point.y - prev.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = -dy / len
                val ny = dx / len
                val localFlicker = 0.78f + 0.22f * burnNoise(seed, i, 1, 1901)
                val cinderOffset = minDim * (0.004f + 0.006f * burnNoise(seed, i, 2, 1931))
                val ember = Offset(point.x + nx * cinderOffset, point.y + ny * cinderOffset)
                drawCircle(
                    color = emberRed.copy(alpha = (0.34f + 0.32f * heat) * edgeLife),
                    radius = (minDim * 0.0062f * (0.70f + heat)).coerceAtLeast(0.8f),
                    center = ember,
                )
                if (heat > 0.28f) {
                    drawCircle(
                        color = emberOrange.copy(alpha = (0.38f + 0.42f * heat * localFlicker) * edgeLife),
                        radius = (minDim * 0.0048f * (0.85f + heat)).coerceAtLeast(0.65f),
                        center = Offset(ember.x - nx * minDim * 0.0032f, ember.y - ny * minDim * 0.0032f),
                    )
                }
                if (heat > 0.54f && burnNoise(seed, i, 4, 1999) > 0.52f) {
                    drawCircle(
                        color = emberYellow.copy(alpha = (0.26f + 0.40f * heat * localFlicker) * edgeLife),
                        radius = (minDim * 0.0026f * (1f + heat)).coerceAtLeast(0.55f),
                        center = Offset(ember.x - nx * minDim * 0.0012f, ember.y - ny * minDim * 0.0012f),
                    )
                }
            }

            if (p > 0.08f && p < 0.92f) {
                val smokeCount = if (aspect > 3f) 3 else 5
                for (i in 0 until smokeCount) {
                    val birth = 0.06f + burnNoise(seed, i, 5, 2101) * 0.62f
                    if (p <= birth) continue
                    val life = ((p - birth) / 0.28f).coerceIn(0f, 1f)
                    if (life >= 1f) continue
                    val xNorm = (burnPrimaryIgnition(seed) + (burnNoise(seed, i, 6, 2137) - 0.5f) * burnPrimarySpread(p) * 1.55f)
                        .coerceIn(0.06f, 0.94f)
                    val frontNorm = burnFrontHeightNormalized(xNorm = xNorm, progress = p, seed = seed, sampleIndex = 300 + i)
                    val cx = size.width * xNorm + (burnNoise(seed, i, 7, 2179) - 0.5f) * size.width * 0.06f * life
                    val cy = size.height * (1f - frontNorm) - minDim * (0.03f + 0.08f * burnNoise(seed, i, 8, 2213)) * life
                    drawCircle(
                        color = smokeColor.copy(alpha = (0.08f * (1f - life)).coerceIn(0f, 0.08f)),
                        radius = (minDim * (0.045f + 0.035f * burnNoise(seed, i, 9, 2237)) * (0.55f + 0.45f * life)).coerceAtLeast(1.5f),
                        center = Offset(cx, cy),
                    )
                }
            }

            drawBurnParticles(progress = p, seed = seed)
        }
}

private fun DrawScope.drawBurnParticles(
    progress: Float,
    seed: Int,
) {
    if (progress <= 0.10f || progress >= 0.985f || size.width <= 0f || size.height <= 0f) return

    val aspect = size.width / size.height.coerceAtLeast(1f)
    val count = if (aspect > 3f) 12 else 24
    val minDim = minOf(size.width, size.height).coerceAtLeast(1f)
    val emberOrange = androidx.compose.ui.graphics.Color(0xFFD94A11)
    val emberYellow = androidx.compose.ui.graphics.Color(0xFFFFD15A)
    val ash = androidx.compose.ui.graphics.Color(0xFF736C66)
    val smoke = androidx.compose.ui.graphics.Color(0xFF918A84)
    val ignite1 = burnPrimaryIgnition(seed)
    val ignite2 = burnSecondaryIgnition(seed, ignite1)
    val spread1 = burnPrimarySpread(progress)
    val spread2 = burnSecondarySpread(progress)

    for (i in 0 until count) {
        val a = burnNoise(seed, i, 2, 3001)
        val b = burnNoise(seed, i, 5, 3049)
        val c = burnNoise(seed, i, 9, 3109)
        val birth = 0.11f + a * 0.68f
        if (progress <= birth) continue
        val life = ((progress - birth) / (0.24f + 0.13f * b)).coerceIn(0f, 1f)
        if (life >= 0.995f) continue

        val useSecond = spread2 > 0.02f && c > 0.74f
        val origin = if (useSecond) ignite2 else ignite1
        val spread = if (useSecond) spread2 else spread1
        val xNorm = (origin + (b - 0.5f) * spread * 1.85f).coerceIn(0.04f, 0.96f)
        val frontNorm = burnFrontHeightNormalized(
            xNorm = xNorm,
            progress = progress,
            seed = seed,
            sampleIndex = 500 + i,
        )
        if (frontNorm <= 0f) continue

        val xBase = size.width * xNorm
        val yBase = size.height * (1f - frontNorm)
        val rise = minDim * (0.09f + 0.28f * b) * life
        val drift = minDim * (b - 0.5f) * 0.22f * life
        val flutter = minDim * sin(life * 11.7f + a * 6.3f) * 0.016f
        val x = xBase + drift + flutter
        val y = yBase - rise
        val hot = (1f - life / 0.42f).coerceIn(0f, 1f)
        val alpha = ((1f - life) * (0.38f + 0.44f * c)).coerceIn(0f, 0.85f)
        val radius = (minDim * (0.0038f + 0.0074f * a) * (1f - life * 0.55f)).coerceAtLeast(0.55f)
        val color = when {
            hot > 0.68f && c > 0.38f -> emberYellow
            hot > 0.16f -> emberOrange
            else -> ash
        }
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y),
        )

        if (c > 0.64f && life > 0.18f) {
            drawCircle(
                color = ash.copy(alpha = alpha * 0.40f),
                radius = (radius * 0.62f).coerceAtLeast(0.4f),
                center = Offset(x - minDim * 0.017f * life, y + minDim * 0.025f * life),
            )
        }
        if (c > 0.82f && life > 0.08f) {
            drawCircle(
                color = smoke.copy(alpha = alpha * 0.16f),
                radius = (radius * 2.4f).coerceAtLeast(0.8f),
                center = Offset(x + minDim * 0.01f * life, y - minDim * 0.02f * life),
            )
        }
    }
}

private fun sandPrimaryOrigin(seed: Int): Float {
    return (0.16f + sandNoise(seed, 17, 7, 4001) * 0.66f).coerceIn(0.08f, 0.92f)
}

private fun sandSecondaryOrigin(seed: Int, primary: Float): Float {
    val shift = (sandNoise(seed, 19, 11, 4049) - 0.5f) * 0.40f
    return (primary + shift).coerceIn(0.08f, 0.92f)
}

private fun sandPrimarySpread(progress: Float): Float {
    val x = smoothStep((progress / 0.28f).coerceIn(0f, 1f))
    return (0.06f + x * 0.42f + progress * 0.18f).coerceIn(0.06f, 0.72f)
}

private fun sandSecondarySpread(progress: Float): Float {
    val on = smoothStep(((progress - 0.18f) / 0.22f).coerceIn(0f, 1f))
    val grow = smoothStep(((progress - 0.26f) / 0.34f).coerceIn(0f, 1f))
    return if (on <= 0.001f) 0f else (0.03f + grow * 0.18f) * on
}

private fun sandFrontHeightNormalized(
    xNorm: Float,
    progress: Float,
    seed: Int,
    sampleIndex: Int,
): Float {
    val p = progress.coerceIn(0f, 1f)
    val rise = smoothStep(((p - 0.02f) / 0.98f).coerceIn(0f, 1f)) * 1.03f
    val origin1 = sandPrimaryOrigin(seed)
    val origin2 = sandSecondaryOrigin(seed, origin1)
    val spread1 = sandPrimarySpread(p)
    val spread2 = sandSecondarySpread(p)
    val lateral1 = maxOf(abs(xNorm - origin1) - spread1, 0f)
    val lateral2 = if (spread2 > 0.0001f) maxOf(abs(xNorm - origin2) - spread2, 0f) else 10f
    val lateral = minOf(lateral1, lateral2)
    val macro = (sandNoise(seed, sampleIndex / 7, 0, 4103) - 0.5f) * 0.16f
    val medium = (sandNoise(seed, sampleIndex / 3, 0, 4151) - 0.5f) * 0.055f
    val fine = (sandNoise(seed, sampleIndex, 0, 4211) - 0.5f) * 0.015f
    val front = rise - lateral * 1.10f - macro - medium - fine
    return front.coerceIn(0f, 1.08f)
}

private fun burnFrontAdvance(progress: Float): Float {
    val x = ((progress - 0.11f) / 0.89f).coerceIn(0f, 1f)
    val eased = smoothStep(x)
    return (eased * 1.02f + x * x * x * 0.08f).coerceIn(0f, 1.1f)
}

private fun burnPrimaryIgnition(seed: Int): Float {
    return (0.18f + burnNoise(seed, 13, 5, 6001) * 0.60f).coerceIn(0.10f, 0.90f)
}

private fun burnSecondaryIgnition(seed: Int, primary: Float): Float {
    val shift = (burnNoise(seed, 17, 7, 6079) - 0.5f) * 0.34f
    return (primary + shift).coerceIn(0.08f, 0.92f)
}

private fun burnPrimarySpread(progress: Float): Float {
    val x = smoothStep((progress / 0.30f).coerceIn(0f, 1f))
    return (0.045f + x * 0.46f + progress * 0.16f).coerceIn(0.045f, 0.70f)
}

private fun burnSecondarySpread(progress: Float): Float {
    val on = smoothStep(((progress - 0.28f) / 0.24f).coerceIn(0f, 1f))
    val grow = smoothStep(((progress - 0.35f) / 0.38f).coerceIn(0f, 1f))
    return if (on <= 0.001f) 0f else (0.018f + grow * 0.17f) * on
}

private fun burnFrontHeightNormalized(
    xNorm: Float,
    progress: Float,
    seed: Int,
    sampleIndex: Int,
): Float {
    val burn = burnFrontAdvance(progress)
    val ignite1 = burnPrimaryIgnition(seed)
    val ignite2 = burnSecondaryIgnition(seed, ignite1)
    val spread1 = burnPrimarySpread(progress)
    val spread2 = burnSecondarySpread(progress)
    val lateral1 = maxOf(abs(xNorm - ignite1) - spread1, 0f)
    val lateral2 = if (spread2 > 0.0001f) maxOf(abs(xNorm - ignite2) - spread2, 0f) else 10f
    val lateral = minOf(lateral1, lateral2)
    val macro = (burnNoise(seed, sampleIndex / 9, 0, 7013) - 0.5f) * 0.18f
    val medium = (burnNoise(seed, sampleIndex / 3, 0, 7069) - 0.5f) * 0.060f
    val fine = (burnNoise(seed, sampleIndex, 0, 7127) - 0.5f) * 0.018f
    val front = burn - lateral * 1.18f - macro - medium - fine
    return front.coerceIn(0f, 1.08f)
}

private fun burnHotPocketMask(
    xNorm: Float,
    progress: Float,
    seed: Int,
    sampleIndex: Int,
): Float {
    val pocketA = burnNoise(seed, sampleIndex, 1, 8011)
    val pocketB = burnNoise(seed, sampleIndex / 2, 3, 8087)
    val pocket = ((pocketA * 0.68f + pocketB * 0.32f) - 0.55f).coerceAtLeast(0f) / 0.45f
    val time = smoothStep(((progress - 0.12f) / 0.18f).coerceIn(0f, 1f))
    val fade = 1f - smoothStep(((progress - 0.86f) / 0.10f).coerceIn(0f, 1f))
    val edgeFocus = (0.82f + 0.18f * burnNoise(seed, (xNorm * 1000).toInt(), 2, 8161)).coerceIn(0f, 1f)
    return (pocket * time * fade * edgeFocus).coerceIn(0f, 1f)
}

private const val BURN_SHADER_SOURCE = """
uniform shader content;
uniform float2 resolution;
uniform float progress;
uniform float seed;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    float h = dot(p, p + float2(45.32 + seed * 19.7, 45.32 + seed * 13.1));
    p += float2(h, h);
    return fract(p.x * p.y);
}

float noise2(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    float2 u = f * f * (float2(3.0, 3.0) - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = noise2(p) * 0.500;
    p = p * 2.03 + float2(17.1, 9.2);
    v += noise2(p) * 0.250;
    p = p * 2.01 + float2(8.3, 19.7);
    v += noise2(p) * 0.125;
    p = p * 2.05 + float2(13.7, 5.4);
    v += noise2(p) * 0.0625;
    p = p * 2.09 + float2(3.6, 23.8);
    v += noise2(p) * 0.03125;
    return v / 0.96875;
}

float burnAdvance(float p) {
    float x = clamp((p - 0.11) / 0.89, 0.0, 1.0);
    float eased = x * x * (3.0 - 2.0 * x);
    return eased * 1.02 + x * x * x * 0.08;
}

float primaryIgniteX() {
    return clamp(0.18 + fract(seed * 17.31 + 0.13) * 0.60, 0.10, 0.90);
}

float secondaryIgniteX(float primary) {
    return clamp(primary + (fract(seed * 43.17 + 0.37) - 0.5) * 0.34, 0.08, 0.92);
}

float primarySpread(float p) {
    float x = smoothstep(0.0, 0.30, p);
    return clamp(0.045 + x * 0.46 + p * 0.16, 0.045, 0.70);
}

float secondarySpread(float p) {
    float on = smoothstep(0.28, 0.52, p);
    float grow = smoothstep(0.35, 0.73, p);
    return (0.018 + grow * 0.17) * on;
}

half4 main(float2 fragCoord) {
    float2 res = max(resolution, float2(1.0, 1.0));
    float2 uv = fragCoord / res;
    float aspect = clamp(res.x / res.y, 0.35, 5.0);
    float p = clamp(progress, 0.0, 1.0);
    if (p <= 0.001) {
        return content.eval(fragCoord);
    }
    if (p >= 0.999) {
        return half4(0.0, 0.0, 0.0, 0.0);
    }

    float ignite1 = primaryIgniteX();
    float ignite2 = secondaryIgniteX(ignite1);
    float spread1 = primarySpread(p);
    float spread2 = secondarySpread(p);
    float lateral1 = max(abs(uv.x - ignite1) - spread1, 0.0);
    float lateral2 = 10.0;
    if (spread2 > 0.0001) {
        lateral2 = max(abs(uv.x - ignite2) - spread2, 0.0);
    }
    float lateral = min(lateral1, lateral2);

    float2 pA = uv * float2(aspect, 1.0);
    float macro = fbm(pA * 3.2 + float2(seed * 7.1, seed * 11.3));
    float medium = fbm(pA * 8.7 + float2(seed * 13.7, seed * 19.5));
    float fine = noise2(pA * 29.0 + float2(seed * 31.1, seed * 17.9));
    float field = (1.0 - uv.y)
        - (burnAdvance(p) - lateral * 1.18)
        + (macro - 0.5) * 0.18
        + (medium - 0.5) * 0.060
        + (fine - 0.5) * 0.018;

    float hotPocket = smoothstep(0.58, 0.86, medium) * (0.62 + 0.38 * fine);
    hotPocket = clamp(hotPocket * 1.20 - 0.04, 0.0, 1.0);
    float glowPocket = smoothstep(0.42, 0.84, medium) * (0.70 + 0.30 * fine);

    float hotBand = (1.0 - smoothstep(0.0015, 0.012, field)) * hotPocket;
    float orangeBand = (1.0 - smoothstep(0.008, 0.030, field)) * hotPocket;
    float redBand = (1.0 - smoothstep(0.016, 0.050, field)) * glowPocket;
    float charBand = (1.0 - smoothstep(0.028, 0.085, field));
    float scorchBand = (1.0 - smoothstep(0.070, 0.180, field));

    float alive = smoothstep(-0.010, 0.008, field);
    float preheatTime = smoothstep(0.02, 0.10, p) * (1.0 - smoothstep(0.17, 0.30, p));
    float preheatBand = (1.0 - smoothstep(0.035, 0.145, field)) * smoothstep(0.010, 0.080, field);
    float preheat = preheatTime * preheatBand;
    float flicker = 0.84 + 0.16 * sin(p * 81.0 + medium * 23.0 + fine * 29.0 + seed * 17.0);
    float distort = clamp(orangeBand * 0.60 + hotBand * 0.95, 0.0, 1.0);

    float2 heatWarp = float2(
        (noise2(uv * float2(23.0 * aspect, 23.0) + float2(seed * 5.7, seed * 9.1)) - 0.5) * 4.0,
        -(noise2(uv * float2(17.0 * aspect, 17.0) + float2(seed * 8.7, seed * 3.9)) - 0.5) * 3.0
    ) * distort;

    half4 src = content.eval(fragCoord + heatWarp);
    if (src.a <= 0.001) {
        return src;
    }

    half3 rgb = src.rgb;
    half3 brown = half3(0.37, 0.21, 0.08) * src.a;
    half3 coal = half3(0.06, 0.04, 0.03) * src.a;
    half3 emberRed = half3(0.55, 0.10, 0.02) * src.a;
    half3 emberOrange = half3(0.86, 0.29, 0.05) * src.a;
    half3 emberYellow = half3(1.0, 0.76, 0.24) * src.a;

    rgb = mix(rgb, brown, half(clamp(preheat * (0.20 + 0.22 * macro), 0.0, 0.36)));
    rgb = mix(rgb, brown, half(clamp(scorchBand * alive * (0.14 + 0.14 * medium), 0.0, 0.46)));
    rgb = mix(rgb, coal, half(clamp(charBand * alive * (0.66 + 0.18 * macro), 0.0, 0.92)));
    rgb = mix(rgb, emberRed, half(clamp(redBand * alive * (0.46 + 0.18 * fine), 0.0, 0.74)));
    rgb = mix(rgb, emberOrange, half(clamp(orangeBand * alive * (0.92 + 0.12 * flicker), 0.0, 0.92)));
    rgb = mix(rgb, emberYellow, half(clamp(hotBand * alive * (0.66 + 0.38 * flicker), 0.0, 0.92)));
    rgb *= half(1.0 - charBand * alive * (0.12 + 0.14 * fine));
    rgb *= half(alive);
    return half4(rgb, src.a * half(alive));
}
"""

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun dissolveNoise(seed: Int, row: Int, column: Int): Float {
    var value = seed xor (row * 0x1f123bb5) xor (column * 0x05491333)
    value = value xor (value ushr 16)
    value *= 0x45d9f3b
    value = value xor (value ushr 16)
    return ((value ushr 8) and 0xFFFF) / 65535f
}

private fun sandNoise(seed: Int, row: Int, column: Int, salt: Int): Float {
    var value = seed xor (row * 0x45d9f3b) xor (column * 0x27d4eb2d) xor (salt * 0x165667b1)
    value = value xor (value ushr 16)
    value *= 0x7feb352d
    value = value xor (value ushr 15)
    value *= 0x846ca68b.toInt()
    value = value xor (value ushr 16)
    return ((value ushr 8) and 0xFFFF) / 65535f
}

private fun burnNoise(seed: Int, row: Int, column: Int, salt: Int): Float {
    var value = seed xor (row * 0x6d2b79f5) xor (column * 0x1b873593) xor (salt * 0x85ebca6b.toInt())
    value = value xor (value ushr 16)
    value *= 0x7feb352d
    value = value xor (value ushr 15)
    value *= 0x846ca68b.toInt()
    value = value xor (value ushr 16)
    return ((value ushr 8) and 0xFFFF) / 65535f
}
