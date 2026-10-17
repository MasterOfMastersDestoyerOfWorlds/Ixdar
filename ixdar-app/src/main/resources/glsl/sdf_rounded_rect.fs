#version 300 es
precision highp float;

in vec3 pos;

out vec4 fragColor;

uniform vec2 center;
uniform vec2 halfSize;
uniform float cornerRadius;
uniform float borderWidth;
uniform vec4 fillColor;
uniform vec4 borderColor;
uniform float edgeSharpness;

// Rounded box distance from Inigo Quilez, https://iquilezles.org/articles/distfunctions2d/ (MIT).
float roundedBoxDistance(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float dist = roundedBoxDistance(pos.xy - center, halfSize, cornerRadius);
    float shape = smoothstep(edgeSharpness, -edgeSharpness, dist);
    float fill = smoothstep(edgeSharpness, -edgeSharpness, dist + borderWidth);
    vec4 color = mix(borderColor, fillColor, fill);
    fragColor = vec4(color.rgb, color.a * shape);
}
