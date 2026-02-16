#version 300 es
precision highp float;
#define PI 3.1415926538

in vec4 vertexColor;
in vec2 scaledTextureCoord;
in vec2 textureCoord;

out vec4 fragColor;

uniform vec2 pointA;
uniform vec2 pointB;
uniform float lineLengthSq;

// Animation uniforms
uniform float dashPhase;  
uniform float dashLength; // Controls arrow spacing

// Styling uniforms
uniform float edgeDist;      // Controls line thickness
uniform float edgeSharpness; // Controls anti-aliasing
uniform vec4 linearGradientColor;
uniform vec4 backgroundColor;
uniform float borderInner;
uniform float borderOuter;
uniform float borderOffsetInner;
uniform float borderOffsetOuter;
uniform vec4 borderColor;

void main() {
    // --- 1. Line Container Logic (Same as your standard line) ---
    // Calculate progress (t) along the line segment (0.0 to 1.0)
    float t = max(0., min(1., dot(scaledTextureCoord - pointA, pointB - pointA) / lineLengthSq));
    
    // Project current pixel onto the line to get distance from center (sigDist)
    vec2 projection = pointA + t * (pointB - pointA);
    float sigDist = distance(scaledTextureCoord, projection);

    // Calculate base opacity for the line shape (handling caps/width)
    float insideLine = smoothstep(edgeDist, edgeDist - edgeSharpness, sigDist);

    // --- 2. Arrow Pattern Logic ---
    
    // Calculate distance along the line (unclamped)
    // We use dot product to project the current coord onto the normalized line vector
    vec2 lineDir = normalize(pointB - pointA);
    vec2 lineNormal = vec2(-lineDir.y, lineDir.x);
    float distAlongLine = dot(scaledTextureCoord - pointA, lineDir);
    float signedPerpDist = dot(scaledTextureCoord - pointA, lineNormal);

    // Clock.spin wraps dashPhase in [0, TAU), so normalize to [0, 1) for seamless motion.
    float normalizedPhase = dashPhase / (2.0 * PI);
    float arrowAngle = 1.0;
    float chevronSpan = edgeDist * arrowAngle;
    float effectiveDashLength = max(chevronSpan * 2.0, 0.0001);

    // Subtract phase so pattern scrolls in the A->B direction (matching chevron pointing).
    float animatedDist = distAlongLine - (normalizedPhase * effectiveDashLength);

    // Create repeating cells: cellX ranges from -chevronSpan to +chevronSpan.
    float cellX = mod(animatedDist + chevronSpan, effectiveDashLength) - chevronSpan;

    // Chevron (>): two parallel V-boundaries create both a V-shaped front edge
    // and a V-shaped back edge — "a rectangle with two triangles taken out."
    float f = cellX + abs(signedPerpDist) * arrowAngle;
    float frontEdge = smoothstep(-edgeSharpness, edgeSharpness, f);
    float backEdge  = 1.0 - smoothstep(chevronSpan - edgeSharpness, chevronSpan + edgeSharpness, f);
    float arrowMask = frontEdge * backEdge;

    // --- 3. Final Composite ---
    
    // Mix vertex color with gradient based on texture position
    vec4 baseColor = mix(vertexColor, linearGradientColor, textureCoord.x);
    float borderMask = smoothstep(borderInner, borderOuter, sigDist)
                     * (1.0 - smoothstep(borderOffsetInner, borderOffsetOuter, sigDist));

    vec4 borderLayer = vec4(borderColor.rgb, borderColor.a * borderMask);
    vec4 backgroundLayer = vec4(backgroundColor.rgb, backgroundColor.a * insideLine);
    vec4 chevronLayer = vec4(baseColor.rgb, baseColor.a * insideLine * arrowMask);

    vec4 composed = borderLayer;
    composed = mix(composed, backgroundLayer, backgroundLayer.a);
    composed = mix(composed, chevronLayer, chevronLayer.a);
    fragColor = composed;
}