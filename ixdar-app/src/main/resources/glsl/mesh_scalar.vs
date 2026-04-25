#version 300 es
precision highp float;

// Per-vertex mesh attributes (matching mesh.vs layout exactly).
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoords;
// PATCH-15: per-vertex scalar, uploaded via setPerVertexScalar(float[]).
// Used for diagnostic heat-map style overlays (Coons reconstruction error,
// curvature magnitude, dihedral scalar, etc). The attribute is interpolated
// across the triangle so intra-patch gradients show continuously.
layout(location = 3) in float aScalar;

out vec3 Normal;
out vec2 TexCoords;
out float vScalar;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
// PATCH-17: see mesh.vs — kept symmetric so future overlay passes in
// SCALAR mode inherit the same depth-bias handling for free.
uniform float depthBias;

void main() {
    gl_Position = projection * view * model * vec4(aPos, 1.0);
    gl_Position.z -= depthBias * gl_Position.w;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    TexCoords = aTexCoords;
    vScalar = aScalar;
}
