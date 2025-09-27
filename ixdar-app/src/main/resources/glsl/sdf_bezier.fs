// version and precision
#version 300 es
precision highp float;

// inputs from vertex shader
in vec4 vertexColor;
in vec2 scaledTextureCoord;

// uniforms
uniform vec2 pointA;
uniform vec2 control;
uniform vec2 pointB;
uniform float thickness;
uniform float width;
uniform float height;
uniform float edgeDist;
uniform float edgeSharpness;

out vec4 fragColor;

float dot2(in vec2 v) { return dot(v, v); }

// signed distance to a quadratic bezier (adapted)
float sdBezier(in vec2 pos, in vec2 A, in vec2 B, in vec2 C) {
    vec2 a = B - A;
    vec2 b = A - 2.0 * B + C;
    vec2 c = a * 2.0;
    vec2 d = A - pos;
    float bb = dot(b, b);
    if (bb < 1e-12) {
        // Degenerate: falls back to distance to segment AC
        vec2 pa = pos - A;
        vec2 ba = C - A;
        float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
        return length(pa - ba * h);
    }
    float kk = 1.0 / bb;
    float kx = kk * dot(a, b);
    float ky = kk * (2.0 * dot(a, a) + dot(d, b)) / 3.0;
    float kz = kk * dot(d, a);
    float p = ky - kx * kx;
    float p3 = p * p * p;
    float q = kx * (2.0 * kx * kx - 3.0 * ky) + kz;
    float h = q * q + 4.0 * p3;
    float res = 0.0;
    if (h >= 0.0) {
        h = sqrt(h);
        vec2 x = (vec2(h, -h) - q) / 2.0;
        vec2 uv = sign(x) * pow(abs(x), vec2(1.0 / 3.0));
        float t = clamp(uv.x + uv.y - kx, 0.0, 1.0);
        res = dot2(d + (c + b * t) * t);
    } else {
        float z = sqrt(-p);
        float v = acos(q / (p * z * 2.0)) / 3.0;
        float m = cos(v);
        float n = sin(v) * 1.732050808;
        vec3 t = clamp(vec3(m + m, -n - m, n - m) * z - kx, 0.0, 1.0);
        res = min(dot2(d + (c + b * t.x) * t.x), dot2(d + (c + b * t.y) * t.y));
    }
    return sqrt(res);
}

void main() {
    vec2 p = scaledTextureCoord;
    float dist = sdBezier(p, pointA, control, pointB);
    float sigDist = dist;
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(vertexColor.rgb, opacity);
}