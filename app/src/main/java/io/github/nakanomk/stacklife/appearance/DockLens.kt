// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// BiliPai design-system LiquidLens.kt @ 5893c2c8 (adapted from Kyant0/AndroidLiquidGlass).
// StackLife: the shader programs are unmodified; bind only full-shape lenses and the dock indicator.
package io.github.nakanomk.stacklife.appearance

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect

internal fun BackdropEffectScope.dockLens(height: Float, amount: Float, depth: Boolean, dispersion: Float, band: Float = -1f) {
    val corners = shape as? CornerBasedShape ?: return
    val limit = size.minDimension * .5f
    if (limit <= 0f) return
    padding = maxOf(padding, amount)
    val sf = downscaleFactor
    val leftToRight = layoutDirection == LayoutDirection.Ltr
    val ts = minOf(corners.topStart.toPx(size, this), limit) / sf
    val te = minOf(corners.topEnd.toPx(size, this), limit) / sf
    val be = minOf(corners.bottomEnd.toPx(size, this), limit) / sf
    val bs = minOf(corners.bottomStart.toPx(size, this), limit) / sf
    val dispersed = dispersion > 0f
    runtimeShaderEffect(if (dispersed) "LiquidGlassLensDispersion" else "LiquidGlassLens",
        if (dispersed) ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER else ROUNDED_RECT_REFRACTION_SHADER, "content") {
        setFloatUniform("size", size.width / sf, size.height / sf)
        setFloatUniform("offset", -padding / sf, -padding / sf)
        if (leftToRight) setFloatUniform("cornerRadii", ts, te, be, bs) else setFloatUniform("cornerRadii", te, ts, bs, be)
        setFloatUniform("refractionHeight", minOf(height, limit) / sf)
        setFloatUniform("refractionAmount", -amount / sf)
        setFloatUniform("depthEffect", if (depth) 1f else 0f)
        if (dispersed) {
            setFloatUniform("chromaticAberration", dispersion)
            setFloatUniform("chromaticEdgeBand", if (band < 0f) -1f else band / sf)
            setFloatUniform("chromaticBandHorizontal", 0f)
        }
    }
}

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x > 0.0 || cornerCoord.y > 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float2 refractionGradient(float2 coord, float2 halfSize, float radius, float depth) {
    float2 grad = gradSdRoundedRect(coord, halfSize, radius);
    if (depth > 0.0) {
        float centerLength = length(coord);
        if (centerLength > 0.0) grad += depth * coord / centerLength;
    }
    float gradLength = length(grad);
    // 中心及轮廓连接点的零梯度保持原位采样，不能归一化为无效坐标。
    if (gradLength == 0.0) return float2(0.0);
    return grad / gradLength;
}
"""

private const val ROUNDED_RECT_REFRACTION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = refractionGradient(centeredCoord, halfSize, gradRadius, depthEffect);

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""

private const val ROUNDED_RECT_REFRACTION_WITH_DISPERSION_SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;
uniform float chromaticEdgeBand;
uniform float chromaticBandHorizontal;

$ROUNDED_RECT_SDF

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = refractionGradient(centeredCoord, halfSize, gradRadius, depthEffect);

    float2 refractedCoord = coord + d * grad;
    // 普通透镜保留原光谱权重；dock 指示器只在选定轴超出轨道的区域色散。
    float dispersionWeight = (centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y);
    if (chromaticEdgeBand >= 0.0) {
        float edgeDistance = chromaticBandHorizontal > 0.5
            ? halfSize.x - abs(centeredCoord.x)
            : halfSize.y - abs(centeredCoord.y);
        // A fixed local-pixel feather makes subpixel bands fade in from zero.
        // Shrinking feather with the band made the outermost pixel jump straight
        // to full dispersion for any positive band (and degenerate at zero).
        float feather = 1.0;
        dispersionWeight = 1.0 - smoothstep(chromaticEdgeBand - feather, chromaticEdgeBand, edgeDistance);
        if (dispersionWeight <= 0.0) return content.eval(refractedCoord);
    }
    float dispersionIntensity = chromaticAberration * dispersionWeight;
    float2 dispersedCoord = d * grad * dispersionIntensity;

    half4 color = half4(0.0);

    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;

    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;

    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;

    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;

    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;

    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;

    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;

    return color;
}
"""
