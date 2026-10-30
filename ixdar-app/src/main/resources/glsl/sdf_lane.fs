#version 300 es
precision highp float;
#define PI 3.1415926538

in vec4 vertexColor;
in vec2 scaledTextureCoord;

uniform vec2 pointA;
uniform vec2 control;
uniform vec2 pointB;
uniform float edgeDist;
uniform float edgeSharpness;
uniform vec4 linearGradientColor;
// Clock.spin phase in [0, TAU): chevrons scroll in their direction of travel.
uniform float dashPhase;
// 1 when traffic runs from pointA to pointB, and when it runs back; with both, the left half of the
// lane carries half-chevrons towards pointB and the right half carries them back.
uniform float forward;
uniform float backward;

out vec4 fragColor;

// Arc length of the piece from its start to t, by five-point Gauss-Legendre over the speed |B'(s)|,
// where B'(s) = c + 2 b s.
float arcLength(vec2 c, vec2 b, float t) {
    float nodes[5] = float[5](0.0, -0.5384693101, 0.5384693101, -0.9061798459, 0.9061798459);
    float weights[5] = float[5](0.5688888889, 0.4786286705, 0.4786286705, 0.2369268851, 0.2369268851);
    float sum = 0.0;
    for (int i = 0; i < 5; i++) {
        sum += weights[i] * length(c + b * (t * (nodes[i] + 1.0)));
    }
    return sum * t * 0.5;
}

// A quadratic Bezier lane piece with butt ends, so pieces drawn end to end at a shared tangent neither
// gap nor overlap. Chevrons follow the curve by arc length, a whole number of them per piece, so they
// line up across every joint whichever leg drew the neighbouring piece.
void main() {
    vec2 a = control - pointA;
    vec2 b = pointA - 2.0 * control + pointB;
    vec2 c = a * 2.0;
    vec2 d = pointA - scaledTextureCoord;
    vec2 chord = pointB - pointA;
    float t;
    if (dot(b, b) < 1e-6 * dot(chord, chord)) {
        t = clamp(dot(-d, chord) / max(dot(chord, chord), 1e-12), 0.0, 1.0);
    } else {
        float kk = 1.0 / dot(b, b);
        float kx = kk * dot(a, b);
        float ky = kk * (2.0 * dot(a, a) + dot(d, b)) / 3.0;
        float kz = kk * dot(d, a);
        float p = ky - kx * kx;
        float q = kx * (2.0 * kx * kx - 3.0 * ky) + kz;
        float h = q * q + 4.0 * p * p * p;
        if (h >= 0.0) {
            h = sqrt(h);
            vec2 x = (vec2(h, -h) - vec2(q, q)) / 2.0;
            vec2 uv = sign(x) * pow(abs(x), vec2(1.0 / 3.0, 1.0 / 3.0));
            t = clamp(uv.x + uv.y - kx, 0.0, 1.0);
        } else {
            float z = sqrt(-p);
            float v = acos(q / (p * z * 2.0)) / 3.0;
            float m = cos(v);
            float n = sin(v) * 1.732050808;
            vec3 t3 = clamp(vec3(m + m, -n - m, n - m) * z - kx, 0.0, 1.0);
            vec2 ex = d + (c + b * t3.x) * t3.x;
            vec2 ey = d + (c + b * t3.y) * t3.y;
            t = dot(ex, ex) < dot(ey, ey) ? t3.x : t3.y;
        }
    }
    vec2 onCurve = pointA + (c + b * t) * t;
    vec2 velocity = c + 2.0 * b * t;
    vec2 tangent = dot(velocity, velocity) > 1e-12 ? normalize(velocity) : normalize(chord + vec2(1e-6, 0.0));
    vec2 offset = scaledTextureCoord - onCurve;
    float lengthwise = dot(offset, tangent);
    if ((t <= 0.0 && lengthwise < 0.0) || (t >= 1.0 && lengthwise > 0.0)) {
        discard;
    }
    float across = dot(offset, vec2(-tangent.y, tangent.x));
    float insideLane = smoothstep(edgeDist, edgeDist - edgeSharpness, abs(across));

    float chevronSpan = edgeDist;
    float total = arcLength(c, b, 1.0);
    float count = max(1.0, floor(total / max(chevronSpan * 2.4, 0.0001) + 0.5));
    float period = total / count;
    float travelled = arcLength(c, b, t);
    bool towardB = forward > 0.5 && (backward < 0.5 || across >= 0.0);
    float along = (towardB ? travelled : total - travelled) - dashPhase / (2.0 * PI) * period;
    float cellX = mod(along + chevronSpan, period) - chevronSpan;
    float f = cellX + abs(across);
    float front = smoothstep(-edgeSharpness, edgeSharpness, f);
    float back = 1.0 - smoothstep(chevronSpan * 0.6 - edgeSharpness, chevronSpan * 0.6 + edgeSharpness, f);
    float moving = max(forward, backward);
    // Both ways: a thin seam of lane colour down the middle keeps the two halves' half-chevrons apart.
    moving *= 1.0 - min(forward, backward) * (1.0 - step(edgeDist * 0.15, abs(across)));
    float chevron = front * back * step(abs(across), edgeDist * 0.7) * moving;

    vec3 color = mix(vertexColor.rgb, linearGradientColor.rgb, chevron);
    fragColor = vec4(color, insideLane * vertexColor.a);
}
