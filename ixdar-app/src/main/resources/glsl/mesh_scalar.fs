#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in float vScalar;
out vec4 FragColor;

// Dark → warm ramp so "low" reads as deep indigo and "high" reads as
// bright yellow-white. Callers pass a scalar already normalised to [0, 1]
// via `scalarMin` / `scalarMax` uniforms — if they don't, values get
// clamped so the extremes still read something sensible.
uniform float scalarMin;
uniform float scalarMax;

vec3 rampColor(float v) {
    // Four-stop thermal ramp: deep navy → wine red → orange → pale yellow.
    // Readable against any patch background and gives a clear visual
    // progression a non-specialist can interpret at a glance.
    v = clamp(v, 0.0, 1.0);
    vec3 c0 = vec3(0.05, 0.05, 0.20);   // deep indigo (low)
    vec3 c1 = vec3(0.55, 0.05, 0.15);   // wine
    vec3 c2 = vec3(0.95, 0.45, 0.05);   // orange
    vec3 c3 = vec3(1.00, 0.95, 0.70);   // pale yellow (high)
    if (v < 0.33) {
        return mix(c0, c1, v / 0.33);
    } else if (v < 0.66) {
        return mix(c1, c2, (v - 0.33) / 0.33);
    } else {
        return mix(c2, c3, (v - 0.66) / 0.34);
    }
}

void main() {
    float range = max(scalarMax - scalarMin, 1e-6);
    float v = (vScalar - scalarMin) / range;
    FragColor = vec4(rampColor(v), 1.0);
}
