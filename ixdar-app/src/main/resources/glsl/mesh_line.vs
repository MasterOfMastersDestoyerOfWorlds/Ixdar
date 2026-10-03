#version 300 es
precision highp float;

// A line drawn on a surface. Each vertex carries the normals of the surface the line lies on:
// an edge's two adjacent faces, or the face a segment crosses twice over.
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aSurfaceNormalA;
layout(location = 2) in vec3 aSurfaceNormalB;

flat out vec3 vSurfaceNormalA;
flat out vec3 vSurfaceNormalB;
out vec3 vViewPosition;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

void main() {
    vec4 viewPosition = view * model * vec4(aPos, 1.0);
    gl_Position = projection * viewPosition;
    mat3 normalMatrix = transpose(inverse(mat3(view * model)));
    vSurfaceNormalA = normalMatrix * aSurfaceNormalA;
    vSurfaceNormalB = normalMatrix * aSurfaceNormalB;
    vViewPosition = viewPosition.xyz;
}
