#version 300 es
precision highp float;

flat in vec3 vSurfaceNormal;
flat in vec3 vCenterView;
flat in vec4 vColor;
flat in float vDiameterPixels;
in vec2 vCorner;
out vec4 FragColor;

uniform mat4 projection;
uniform vec2 viewportSize;

// Steepest face, as eye-depth change per unit across the view, whose plane the disc follows
// exactly; a steeper (grazing) face's plane is followed only this far toward the eye, since its
// ray hit runs off far from the vertex and would lift the disc through nearer surface.
const float STEEPEST_FOLLOWED_SLOPE = 4.0;

// Window depth where the ray through this pixel's centre meets the plane of the face this copy
// of the disc carries, or 1 (the far plane) when the face is turned away or the ray misses it.
float surfaceDepth(vec3 normal, vec3 rayOrigin, vec3 rayDirection, vec3 toEye,
        float nearestViewZ) {
    float facing = dot(normal, toEye);
    float approach = dot(normal, rayDirection);
    if (facing <= 0.0 || approach >= 0.0) {
        return 1.0;
    }
    vec3 hit = rayOrigin + rayDirection * (dot(normal, vCenterView - rayOrigin) / approach);
    hit.z = min(hit.z, nearestViewZ);
    vec4 clip = projection * vec4(hit, 1.0);
    return clip.z / clip.w * 0.5 + 0.5;
}

// The same depth rule as mesh_line.fs, with no bias: a disc copy on a face turned away from the
// camera draws nothing, and each fragment takes the depth of its face's plane at the pixel
// centre, so it ties the face it lies on and LEQUAL passes it while nearer surface hides it. The
// copies of one vertex together cover every face around it; one with no known face (a zero
// normal) keeps the vertex's own depth.
void main() {
    if (dot(vCorner, vCorner) > 1.0) {
        discard;
    }
    bool orthographic = projection[3][3] == 1.0;
    vec3 toEye = orthographic ? vec3(0.0, 0.0, 1.0) : -vCenterView;
    if (dot(vSurfaceNormal, toEye) < 0.0) {
        discard;
    }
    vec2 ndc = gl_FragCoord.xy / viewportSize * 2.0 - 1.0;
    mat4 unproject = inverse(projection);
    vec4 nearPoint = unproject * vec4(ndc, -1.0, 1.0);
    vec4 farPoint = unproject * vec4(ndc, 1.0, 1.0);
    vec3 rayOrigin = nearPoint.xyz / nearPoint.w;
    vec3 rayDirection = farPoint.xyz / farPoint.w - rayOrigin;
    float pixelWorld = 2.0 * (orthographic ? 1.0 : -vCenterView.z)
            / (projection[1][1] * viewportSize.y);
    float nearestViewZ =
            vCenterView.z + STEEPEST_FOLLOWED_SLOPE * 0.5 * vDiameterPixels * pixelWorld;
    gl_FragDepth = min(gl_FragCoord.z, surfaceDepth(vSurfaceNormal, rayOrigin, rayDirection,
            toEye, nearestViewZ));
    FragColor = vColor;
}
