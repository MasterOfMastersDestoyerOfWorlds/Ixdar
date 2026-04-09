#version 300 es
precision highp float;

in vec3 Normal;
in vec2 TexCoords;
in vec4 VertexColor;
out vec4 FragColor;

uniform sampler2D albedoTex;
uniform vec4 solidColor;
uniform bool useTexture;
uniform vec3 lightDir;
uniform vec3 emissiveColor;
uniform float emissiveStrength;
uniform float rimStrength;

void main() {
    vec3 n = normalize(Normal);
    float diffuse = max(dot(n, normalize(-lightDir)), 0.2);
    vec4 base = useTexture ? texture(albedoTex, TexCoords) : solidColor;
    float rim = pow(max(1.0 - n.z, 0.0), 2.0);
    vec3 emissive = emissiveColor * (emissiveStrength + rim * rimStrength);
    vec3 finalColor = base.rgb * diffuse + emissive;
    FragColor = vec4(finalColor * VertexColor.rgb, base.a * VertexColor.a);
}
