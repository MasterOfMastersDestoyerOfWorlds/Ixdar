#version 330 core
in vec4 vertexColor;
in vec2 textureCoord;

out vec4 fragColor;

uniform sampler2D texImage;
float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
void main() {
    vec2 flipped_texCoords = vec2(textureCoord.x, textureCoord.y);
    vec3 sample = texture(texImage, flipped_texCoords).rgb;
    float sigDist = median(sample.r, sample.g, sample.b);
    float w = fwidth(sigDist);
    float opacity = smoothstep(0.5 - w, 0.5 + w, sigDist);
    fragColor = vec4(vertexColor.rgb, vertexColor.a * opacity);
}