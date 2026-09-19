#version 300 es
precision highp float;

flat in vec3 vFaceId;
out vec4 FragColor;

void main() {
    FragColor = vec4(vFaceId, 1.0);
}
