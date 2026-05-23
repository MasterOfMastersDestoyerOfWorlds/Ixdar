#version 300 es
#extension GL_OES_standard_derivatives : enable
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in vec2 vUv;

in float vFlipped;
out vec4 FragColor;

// Background fill where the iso-line indicators are inactive.
uniform vec4 baseColor;
// Tint for iso-lines along constant-u (vertical-in-parameter-space) curves.
uniform vec4 uLineColor;
// Tint for iso-lines along constant-v curves.
uniform vec4 vLineColor;
// Half-width of an iso-line, expressed in screen-space pixels of vUv
// gradient (i.e. multiplied through fwidth(vUv) so the line stays visually
// constant under zoom).
uniform float lineHalfWidth;
uniform vec4 flippedColor; 

void main() {
    // Distance to the nearest integer iso-line on each axis. fract(x + 0.5)
    // ranges [0, 1) and the abs(... - 0.5) folds it back to [0, 0.5] with
    // zero exactly at an integer. fwidth gives the per-pixel parametric
    // gradient so the smoothstep produces a constant-width line in screen
    // space regardless of how stretched the UV is on this triangle.
    vec2 gradient = fwidth(vUv);
    vec2 distToIso = abs(fract(vUv + 0.5) - 0.5);
    vec2 lineMask = vec2(1.0) - smoothstep(
            vec2(0.0), gradient * lineHalfWidth, distToIso);

    // Composite: start from base, then over-paint v-lines (constant-v
    // means changing in u; the line therefore lives where v is near an
    // integer, which is lineMask.y) and u-lines (lineMask.x).
    vec3 colour = baseColor.rgb;
    colour = mix(colour, vLineColor.rgb, lineMask.y * vLineColor.a);
    colour = mix(colour, uLineColor.rgb, lineMask.x * uLineColor.a);
    colour = mix(colour, flippedColor.rgb, vFlipped * flippedColor.a);
    FragColor = vec4(colour, baseColor.a);
}
