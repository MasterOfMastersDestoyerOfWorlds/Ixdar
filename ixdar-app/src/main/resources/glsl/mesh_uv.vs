#version 300 es
precision highp float;

// Per-vertex mesh attributes (matching mesh.vs layout for positions/normals).
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec2 aTexCoords;
// Per-corner seamless parametrization (u, v), uploaded as a triangle-soup
// because the values are discontinuous across BZK09 §5 cut edges and so
// cannot share GPU vertices the way positions can.
layout(location = 3) in vec2 aUv;
layout(location = 4) in float aFlipped;
out float vFlipped;

out vec3 Normal;
out vec2 TexCoords;
out vec2 vUv;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
// Same depth-bias plumbing as mesh.vs / mesh_scalar.vs; overlay passes can
// nudge geometry forward to beat z-fight without changing world position.
uniform float depthBias;

void main() {
    gl_Position = projection * view * model * vec4(aPos, 1.0);
    gl_Position.z -= depthBias * gl_Position.w;
    Normal = mat3(transpose(inverse(model))) * aNormal;
    TexCoords = aTexCoords;
    vUv = aUv;
    vFlipped = aFlipped;
}
