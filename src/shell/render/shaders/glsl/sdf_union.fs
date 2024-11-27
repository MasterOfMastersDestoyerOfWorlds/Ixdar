#version 330 core
#define PI 3.1415926538
#define TAU 2*3.1415926538
in vec4 vertexColor;
in vec2 textureCoord;

out vec4 fragColor;

uniform bool sharpCorners;

uniform vec4 borderColor;

uniform sampler2D innerTexture;
uniform sampler2D outerTexture;

uniform float innerScaleX;
uniform float innerScaleY;
uniform float innerOffsetX;
uniform float innerOffsetY;

uniform int numberPinStripes;
uniform int showPin;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}
float map(float value, float min1, float max1, float min2, float max2) {
    return min2 + (value - min1) * (max2 - min2) / (max1 - min1);
}
float opac(sampler2D tex, float scaleX, float scaleY, float offsetX, float offsetY) {

    vec2 newUvForTexture = vec2((textureCoord.x - 0.5) * scaleX + 0.5 + offsetX, (textureCoord.y - 0.5) * scaleY + 0.5 + offsetY);

    vec2 clampedUVs = clamp(newUvForTexture, vec2(0.0, 0.0), vec2(1.0, 1.0));
    vec4 sample = texture(tex, clampedUVs);
    float sigDist = median(sample.r, sample.g, sample.b);
    float w = fwidth(sigDist);
    float opacity = smoothstep(0.5 - w, 0.5 + w, sigDist);
    return opacity;
}
void main() {
    vec2 newUvForTexture = vec2((textureCoord.x - 0.5) * innerScaleX + 0.5 + innerOffsetX, (textureCoord.y - 0.5) * innerScaleY + 0.5 + innerOffsetY);
    vec2 clampedUVs = clamp(newUvForTexture, vec2(0.0, 0.0), vec2(1.0, 1.0));
    if(clampedUVs.x > 1) {
        clampedUVs.x = 0;
    }
    if(clampedUVs.y > 1) {
        clampedUVs.y = 0;
    }

    vec4 sample = texture(innerTexture, clampedUVs);
    float sigDist = median(sample.r, sample.g, sample.b);
    float pinRadians = sigDist * TAU * numberPinStripes;
    float pinStripes = smoothstep(0.5, 0.7, ((1 + sin(pinRadians)) / 2));
    float pinNumber = numberPinStripes - ((pinRadians) / (TAU));
    if(pinNumber > showPin || pinNumber < showPin - 1) {
        pinStripes = 0;
    }
    float w = fwidth(sigDist);
    float innerOpac = smoothstep(0.5 - w, 0.5 + w, sigDist) - pinStripes;// 

    float outerOpac = opac(outerTexture, 1, 1, 0, 0);

    fragColor = mix(vec4(borderColor.rgb, borderColor.a * outerOpac), vertexColor, innerOpac);
    float var = smoothstep(0.5, 0.7, ((1 + sin(pinRadians)) / 2));
    //fragColor = vec4(var, var, var, 1);
}