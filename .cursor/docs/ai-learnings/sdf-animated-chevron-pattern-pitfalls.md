---
title: SDF Animated Chevron Pattern Pitfalls
category: architecture
severity: high
modules: [graphics]
tags: [sdf, shader, animation, chevron, arrow-line, glsl, geometry]
---

# SDF Animated Chevron Pattern Pitfalls

## Context
Building an animated chevron/arrow pattern on an SDF line segment. The pattern uses `mod()` cell repetition, a diagonal V-field for the chevron shape, and a time-based phase for animation. Multiple iterations were needed to get shape, direction, and spacing correct.

## Decision

1. **Chevron shape requires TWO V-boundaries**: A chevron is NOT a filled wedge. It is a shape with a V-shaped front edge AND a V-shaped back edge — "a rectangle with two triangles taken out." Use two `smoothstep` calls on the same diagonal field `f`:
   ```glsl
   float f = cellX + abs(signedPerpDist) * arrowAngle;
   float frontEdge = smoothstep(-ee, ee, f);
   float backEdge  = 1.0 - smoothstep(span - ee, span + ee, f);
   float arrowMask = frontEdge * backEdge;
   ```
   A single `smoothstep(f)` only creates the front V; the back defaults to the flat cell boundary, producing a filled triangle/wedge — visually wrong.

2. **Animation direction**: **Subtract** the phase offset. `distAlongLine - phase * cellSize` scrolls the pattern in the A→B direction (matching the ">" pointing direction). Adding the phase scrolls toward A (backward). This is counterintuitive — think of it like a scrolling filmstrip where subtracting the offset pulls new content from the right.

3. **Equal negative-space spacing**: With `effectiveDashLength = 2 * chevronSpan`, each cell splits into two equal halves in f-space. One half is the chevron band (f from 0 to chevronSpan), the other is the gap. Both halves are bounded by V-shaped edges, so the gap is the same shape as the chevron.

4. **Perpendicular distance for chevron shape**: Use distance to the **infinite** line axis, not `sigDist` (clamped to segment). Near line caps, clamped distance becomes radial, warping V shapes into U shapes:
   ```glsl
   vec2 lineNormal = vec2(-lineDir.y, lineDir.x);
   float signedPerpDist = dot(coord - pointA, lineNormal);
   ```
   Keep `sigDist` only for the line boundary mask (`insideLine`).

## Evidence
- Single-boundary approach produced filled wedges ("rectangles with one triangle removed") — confirmed via screenshot automation.
- Subtracting phase produces correct A→B flow; adding phase was visually backward.
- Two-boundary band with `cellSize = 2 * chevronSpan` produces matching chevron and gap shapes — validated via screenshot.

## Reuse Trigger
Any SDF line shader using `mod()` cell repetition with a diagonal shape (chevrons, arrows, zigzags) and time-based animation.

## Anti-pattern
- **Single V-boundary for chevron**: `smoothstep(f)` alone gives a filled wedge, not a chevron. Always pair with a back boundary.
- **Using `sigDist` for interior pattern geometry**: It warps near line caps. Use infinite-line perpendicular distance.
- **Assuming add-phase = forward**: Adding phase scrolls toward the line origin (A), not toward B.
- **Stroke-based thin outlines for chevrons**: Using `abs(f) < thickness` with small thickness gives thin hollow V-lines, not the filled chevron band shape. Use the two-boundary band approach instead.
