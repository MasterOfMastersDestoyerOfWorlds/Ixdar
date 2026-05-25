#version 300 es
#extension GL_OES_standard_derivatives : enable
precision highp float;

in vec3 Normal;
in vec2 vUv;
in float vFlipped;
in vec4 vTrace0;
in vec4 vTrace1;
in vec4 vTrace2;
in vec4 vTrace3;
flat in float vPatchId;

out vec4 FragColor;

uniform vec4 baseColor;
uniform vec4 uLineColor;
uniform vec4 vLineColor;
uniform float lineHalfWidth;
uniform vec4 flippedColor;
uniform float drawFullIsoGrid;
uniform float usePatchColor;

vec3 patchColor(float patchId) {
    // Deterministic hue per patch id via a small integer hash mapped to HSV.
    uint seed = uint(int(patchId) + 1);
    seed = (seed ^ 0x27d4eb2du) * 0x9e3779b9u;
    seed ^= (seed >> 16);
    float hue = fract(float(seed) * (1.0 / 4294967296.0));
    float sat = 0.55;
    float val = 0.85;
    float h = hue * 6.0;
    float c = val * sat;
    float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
    vec3 rgb;
    if (h < 1.0)      rgb = vec3(c, x, 0.0);
    else if (h < 2.0) rgb = vec3(x, c, 0.0);
    else if (h < 3.0) rgb = vec3(0.0, c, x);
    else if (h < 4.0) rgb = vec3(0.0, x, c);
    else if (h < 5.0) rgb = vec3(x, 0.0, c);
    else              rgb = vec3(c, 0.0, x);
    return rgb + (val - c);
}

float traceLineMask(vec4 record, vec2 uv, out float isULine) {
    isULine = 0.0;
    if (record.y == 0.0 && record.z == 0.0 && record.w == 0.0) {
        return 0.0;
    }
    bool holdsUConstant = record.x > 0.5;
    float iso = record.y;
    float spanStart = min(record.z, record.w);
    float spanEnd = max(record.z, record.w);
    float lineCoord = holdsUConstant ? uv.x : uv.y;
    float spanCoord = holdsUConstant ? uv.y : uv.x;
    if (spanCoord < spanStart || spanCoord > spanEnd) {
        return 0.0;
    }
    vec2 gradient = fwidth(uv);
    float grad = holdsUConstant ? gradient.x : gradient.y;
    float dist = abs(lineCoord - iso);
    isULine = holdsUConstant ? 1.0 : 0.0;
    return 1.0 - smoothstep(0.0, grad * lineHalfWidth, dist);
}

void accumulateTrace(vec4 record, vec2 uv, inout float uMask, inout float vMask) {
    float isULine;
    float mask = traceLineMask(record, uv, isULine);
    if (isULine > 0.5) {
        uMask = max(uMask, mask);
    } else {
        vMask = max(vMask, mask);
    }
}

void main() {
    vec2 gradient = fwidth(vUv);
    vec2 distToIso = abs(fract(vUv + 0.5) - 0.5);
    vec2 gridMask = vec2(1.0) - smoothstep(vec2(0.0), gradient * lineHalfWidth, distToIso);

    vec3 colour = baseColor.rgb;
    if (usePatchColor > 0.5 && vPatchId >= 0.0) {
        colour = patchColor(vPatchId);
    }
    if (drawFullIsoGrid > 0.5) {
        colour = mix(colour, vLineColor.rgb, gridMask.y * vLineColor.a);
        colour = mix(colour, uLineColor.rgb, gridMask.x * uLineColor.a);
    }

    float uTraceMask = 0.0;
    float vTraceMask = 0.0;
    accumulateTrace(vTrace0, vUv, uTraceMask, vTraceMask);
    accumulateTrace(vTrace1, vUv, uTraceMask, vTraceMask);
    accumulateTrace(vTrace2, vUv, uTraceMask, vTraceMask);
    accumulateTrace(vTrace3, vUv, uTraceMask, vTraceMask);

    colour = mix(colour, uLineColor.rgb, uTraceMask * uLineColor.a);
    colour = mix(colour, vLineColor.rgb, vTraceMask * vLineColor.a);
    colour = mix(colour, flippedColor.rgb, vFlipped * flippedColor.a);
    FragColor = vec4(colour, baseColor.a);
}
