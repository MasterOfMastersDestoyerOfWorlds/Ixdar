#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in float vScalar;
out vec4 FragColor;

// Callers pass the scalar's range in `scalarMin` / `scalarMax`; values outside it clamp to the
// ramp's ends.
uniform float scalarMin;
uniform float scalarMax;

// The worn Palette's scalar ramp, low to high, at evenly spaced stops; RAMP_STOPS is its size.
const int RAMP_STOPS = 4;
uniform vec4 rampColors[RAMP_STOPS];

vec3 rampColor(float v) {
    float position = clamp(v, 0.0, 1.0) * float(RAMP_STOPS - 1);
    int low = min(int(position), RAMP_STOPS - 2);
    return mix(rampColors[low].rgb, rampColors[low + 1].rgb, position - float(low));
}

void main() {
    float range = max(scalarMax - scalarMin, 1e-6);
    float v = (vScalar - scalarMin) / range;
    FragColor = vec4(rampColor(v), 1.0);
}
