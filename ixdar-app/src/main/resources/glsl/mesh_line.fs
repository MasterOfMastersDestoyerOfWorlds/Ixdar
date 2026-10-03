#version 300 es
precision highp float;

flat in vec3 vSurfaceNormalA;
flat in vec3 vSurfaceNormalB;
in vec3 vViewPosition;
out vec4 FragColor;

uniform mat4 projection;
uniform vec2 viewportSize;
uniform vec4 solidColor;

// Window depth where the ray through this pixel's centre meets the plane of one face the line
// lies on, or 1 (the far plane) when the face is turned away or the ray misses it.
float surfaceDepth(vec3 normal, vec3 rayOrigin, vec3 rayDirection, vec3 toEye) {
    float facing = dot(normal, toEye);
    float approach = dot(normal, rayDirection);
    if (facing <= 0.0 || approach >= 0.0) {
        return 1.0;
    }
    vec3 hit = rayOrigin + rayDirection * (dot(normal, vViewPosition - rayOrigin) / approach);
    vec4 clip = projection * vec4(hit, 1.0);
    return clip.z / clip.w * 0.5 + 0.5;
}

// A line on surface that faces away from the camera is on the far side of the model: drop it,
// so the depth test only has to sort front-facing surface against front-facing surface. Zero
// normals mark a line whose surface is unknown, which is never dropped.
//
// A line fragment sits where its pixel projects onto the line, up to half a pixel from the pixel
// centre the faces beside it are depth-tested at; on a steep face that gap is many depth steps.
// The fragment instead takes the depth of its own surface at the pixel centre, so it ties the
// face it lies on and the LEQUAL depth test passes it, while nearer surface still hides it.
// Canvas3D sets filled polygons back one resolvable depth step (polygon offset units, no slope
// factor) so the float rounding between this depth and the rasterizer's never flips that tie.
void main() {
    // An orthographic projection keeps w = 1 (m33 = 1): the eye looks along view-space -z from
    // infinity. In perspective the eye sits at the view-space origin.
    bool orthographic = projection[3][3] == 1.0;
    vec3 toEye = orthographic ? vec3(0.0, 0.0, 1.0) : -vViewPosition;
    if (dot(vSurfaceNormalA, toEye) < 0.0 && dot(vSurfaceNormalB, toEye) < 0.0) {
        discard;
    }
    vec2 ndc = gl_FragCoord.xy / viewportSize * 2.0 - 1.0;
    mat4 unproject = inverse(projection);
    vec4 nearPoint = unproject * vec4(ndc, -1.0, 1.0);
    vec4 farPoint = unproject * vec4(ndc, 1.0, 1.0);
    vec3 rayOrigin = nearPoint.xyz / nearPoint.w;
    vec3 rayDirection = farPoint.xyz / farPoint.w - rayOrigin;
    gl_FragDepth = min(gl_FragCoord.z, min(
            surfaceDepth(vSurfaceNormalA, rayOrigin, rayDirection, toEye),
            surfaceDepth(vSurfaceNormalB, rayOrigin, rayDirection, toEye)));
    FragColor = solidColor;
}
