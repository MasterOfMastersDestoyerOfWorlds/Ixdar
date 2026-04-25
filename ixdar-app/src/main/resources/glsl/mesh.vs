#version 300 es
precision highp float;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoords;

out vec3 Normal;
out vec2 TexCoords;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
// PATCH-17: per-draw depth-bias applied in clip space. 0 for face passes;
// small positive (≈3e-4) for overlay line passes so edges sit marginally
// in front of the faces they border without visible floating. Multiplied
// by gl_Position.w so the bias translates to a consistent view-space
// offset regardless of distance.
uniform float depthBias;

void main() {
    gl_Position = projection * view * model * vec4(aPos, 1.0);
    gl_Position.z -= depthBias * gl_Position.w;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    TexCoords = aTexCoords;
}
