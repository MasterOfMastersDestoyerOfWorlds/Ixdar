#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;
uniform vec3 emissiveColor;
uniform float emissiveStrength;
uniform float rimStrength;
uniform mat4 view;

void main() {
    vec3 n = normalize(Normal);
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    vec4 base = useTexture ? texture(albedoTex, TexCoords) : solidColor;
    float facing = normalize(mat3(view) * n).z;
    float rim = pow(max(1.0 - facing, 0.0), 2.0);
    vec3 emissive = emissiveColor * (emissiveStrength + rim * rimStrength);
    FragColor = vec4(base.rgb * diffuse + emissive, base.a);
}
