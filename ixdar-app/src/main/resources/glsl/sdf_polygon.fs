#version 300 es
precision highp float;

in vec4 vertexColor;
in vec2 scaledTextureCoord;

// Up to eight corners in texture space, two per vec4; cornerCount says how many are used.
uniform vec4 corners01;
uniform vec4 corners23;
uniform vec4 corners45;
uniform vec4 corners67;
uniform float cornerCount;
uniform float edgeSharpness;

out vec4 fragColor;

vec2 corner(int i) {
    if (i == 0) return corners01.xy;
    if (i == 1) return corners01.zw;
    if (i == 2) return corners23.xy;
    if (i == 3) return corners23.zw;
    if (i == 4) return corners45.xy;
    if (i == 5) return corners45.zw;
    if (i == 6) return corners67.xy;
    return corners67.zw;
}

// Signed distance to a simple polygon, negative inside. Ported from Inigo Quilez's sdPolygon,
// https://iquilezles.org/articles/distfunctions2d/ (MIT licence).
void main() {
    int count = int(cornerCount + 0.5);
    vec2 p = scaledTextureCoord;
    float d = dot(p - corner(0), p - corner(0));
    float s = 1.0;
    for (int i = 0, j = count - 1; i < 8; j = i, i++) {
        if (i >= count) {
            break;
        }
        vec2 vi = corner(i);
        vec2 vj = corner(j);
        vec2 e = vj - vi;
        vec2 w = p - vi;
        vec2 b = w - e * clamp(dot(w, e) / dot(e, e), 0.0, 1.0);
        d = min(d, dot(b, b));
        bvec3 cond = bvec3(p.y >= vi.y, p.y < vj.y, e.x * w.y > e.y * w.x);
        if (all(cond) || all(not(cond))) {
            s = -s;
        }
    }
    float signedDist = s * sqrt(d);
    float opacity = smoothstep(edgeSharpness, -edgeSharpness, signedDist);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * opacity);
}
