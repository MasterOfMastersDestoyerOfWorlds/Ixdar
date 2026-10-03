#version 300 es
precision highp float;

// One corner of a screen-space disc around a surface vertex, a fixed number of framebuffer
// pixels across at any zoom. The disc is drawn once per face around the vertex, each copy
// carrying that face's normal so the fragment shader can depth-test it against its own surface.
layout(location = 0) in vec3 aCenter;
layout(location = 1) in vec2 aCorner;
layout(location = 2) in vec3 aSurfaceNormal;
layout(location = 3) in vec3 aColor;
layout(location = 4) in float aDiameterPixels;

flat out vec3 vSurfaceNormal;
flat out vec3 vCenterView;
flat out vec4 vColor;
flat out float vDiameterPixels;
out vec2 vCorner;

uniform mat4 model;
uniform mat4 view;
uniform mat4 projection;
uniform vec2 viewportSize;

void main() {
    vec4 viewPosition = view * model * vec4(aCenter, 1.0);
    vec4 clip = projection * viewPosition;
    // Centre the disc on a pixel centre so an odd diameter covers the same pixels wherever the
    // vertex lands, then push the corner out by half the diameter in pixels.
    vec2 pixel = floor((clip.xy / clip.w * 0.5 + 0.5) * viewportSize) + 0.5;
    vec2 cornerPixel = pixel + aCorner * aDiameterPixels * 0.5;
    clip.xy = (cornerPixel / viewportSize * 2.0 - 1.0) * clip.w;
    gl_Position = clip;
    mat3 normalMatrix = transpose(inverse(mat3(view * model)));
    vSurfaceNormal = normalMatrix * aSurfaceNormal;
    vCenterView = viewPosition.xyz;
    vColor = vec4(aColor, 1.0);
    vDiameterPixels = aDiameterPixels;
    vCorner = aCorner;
}
