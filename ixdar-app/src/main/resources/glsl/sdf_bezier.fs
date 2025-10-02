
#version 300 es
precision highp float;

in vec4 vertexColor;
in vec2 scaledTextureCoord;

uniform vec2 pointA;
uniform vec2 control;
uniform vec2 pointB;
uniform float edgeDist;
uniform float edgeSharpness;
uniform float inverseLineLengthSq;

out vec4 fragColor;



void main() {
    vec2 a = control - pointA;
    vec2 b = pointA - 2.0 * control + pointB;
    vec2 c = a * 2.0;
    vec2 d = pointA - scaledTextureCoord;
    float bb = dot(b, b);
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
        vec2 ree = d + (c + b * t) * t;
        res = dot(ree, ree);
    } else {
        float z = sqrt(-p);
        float v = acos(q / (p * z * 2.0)) / 3.0;
        float m = cos(v);
        float n = sin(v) * 1.732050808;
        vec3 t = clamp(vec3(m + m, -n - m, n - m) * z - kx, 0.0, 1.0);
        vec2 reex = d + (c + b * t.x) * t.x;
        vec2 reey = d + (c + b * t.y) * t.y;
        res = min(dot(reex, reex), dot(reey, reey));
    }
    float dist = sqrt(res);
    float sigDist = dist;
    float opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
    fragColor = vec4(vertexColor.rgb, opacity);

    sigDist = -1.;
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) * inverseLineLengthSq));
    vec2 projection = pointA + t * (pointB - pointA);
    sigDist = distance(scaledTextureCoord, projection);
    opacity = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);
}