---
title: ByteBuffer.allocateDirect() Per-Frame is a CPU Killer
category: performance
severity: critical
modules: [graphics, platform]
tags: [rendering, buffers, performance, opengl, native-memory]
promoted_to: ixdar-coding-standards.mdc
---

# ByteBuffer.allocateDirect() Per-Frame is a CPU Killer

## Context
IrregularGridScene was spiking to 20% CPU. Profiling showed `ShaderProgram.setVec2/setVec4/setMat4` and `ShaderDrawable.uploadGeometry` allocating new direct ByteBuffers via `ByteBuffer.allocateDirect()` on every call, every frame. Direct buffers are native memory with GC finalizer overhead -- hundreds of allocations per frame burned ~2,400ms CPU.

## Decision
Cache reusable `IxBuffer` fields in `ShaderProgram` (vec2Buf, vec3Buf, vec4Buf, mat4Buf) and `ShaderDrawable` (geometryBuf). Lazy-init on first use, then `clear()` and reuse. Same data written, zero allocations after warmup.

## Evidence
Profiling call tree: `allocateFloats -> DefaultBuffer.<init> -> ByteBuffer.allocateDirect` was the #1 CPU hotspot at 1,175ms (setVec2) + 931ms (setVec4) + 200ms (uploadGeometry). Fix eliminates all of these.

## Reuse Trigger
Any time a `platform.allocateFloats()` call appears inside a per-frame or per-draw-call path. Check `setVec*`, `setMat*`, and any `uploadGeometry` variants.

## Anti-pattern
Never call `ByteBuffer.allocateDirect()` in a render loop. Direct buffers are for one-time allocation, not per-frame use. If you see `platform.allocateFloats()` inside `draw()`, `setup()`, or `setUniforms()`, it's a bug.
