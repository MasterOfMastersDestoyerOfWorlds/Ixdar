#version 300 es
precision highp float;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoords;
// A field look's per-vertex value (HalfEdgeMeshRuntime.setPerVertexScalar); 0 while none is bound.
layout(location = 3) in float aScalar;

out vec3 Normal;
out vec2 TexCoords;
out vec3 ViewPosition;
out float vScalar;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;

void main() {
    vec4 viewPosition = view * model * vec4(aPos, 1.0);
    gl_Position = projection * viewPosition;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    TexCoords = aTexCoords;
    ViewPosition = viewPosition.xyz;
    vScalar = aScalar;
}
