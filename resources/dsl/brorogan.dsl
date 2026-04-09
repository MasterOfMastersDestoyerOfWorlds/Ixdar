# BroRogan — Procedural approximation via displacement sphere
# Reference: /Users/acw28/Blends/exports/BroRogan.obj
# Original stats: 2,056,764 verts, 4,113,524 faces
# Dimensions: X=2.8555, Y=6.0931, Z=4.2101
# Aspect ratio: ~1 : 2.13 : 1.47 (X:Y:Z)
# Center: (1.1756, -1.5140, -0.2690)
#
# Visual structure: Head with brain-like convoluted top, facial features below
# Strategy: UV sphere with subtle displacement for brain convolutions + face

# ── Parameters ────────────────────────────────────────────────────────
subdivisions = input_int(name="subdivisions", default=6, min=4, max=8)
brain_convolution_depth = input_float(name="brain_convolution_depth", default=0.08, min=0.01, max=0.3)
face_depth = input_float(name="face_depth", default=0.06, min=0.01, max=0.3)

# ── Base: UV sphere stretched to ellipsoid proportions ─────────────────
base_sphere = uv_sphere(radius=1.0, segments=64, rings=48)

# Scale to match BroRogan proportions
scaled_sphere = transform_geometry(
    geometry=base_sphere.mesh,
    translation=<0.0, 0.0, 0.0>,
    scale=<1.42775, 3.04655, 2.10505>
)

# ── Displacement: create brain convolutions and facial features ─────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
nrm = input_normal()

# Normalize coordinates to [-1, 1] range relative to sphere center
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=1.42775)
ny = float_math(operation=DIVIDE, a=pos_xyz.y, b=3.04655)
nz = float_math(operation=DIVIDE, a=pos_xyz.z, b=2.10505)

# === Brain region (top half, ny > 0) ===
brain_gate_raw = float_math(operation=MAXIMUM, a=ny.result, b=0.0)

# Multi-scale brain convolutions using sine waves
brain_freq1 = float_math(operation=MULTIPLY, a=ny.result, b=8.0)
brain_sin1 = float_math(operation=SINE, a=brain_freq1.result, b=0.0)
brain_freq2 = float_math(operation=MULTIPLY, a=ny.result, b=16.0)
brain_sin2 = float_math(operation=SINE, a=brain_freq2.result, b=0.0)
brain_freq3 = float_math(operation=MULTIPLY, a=ny.result, b=24.0)
brain_sin3 = float_math(operation=SINE, a=brain_freq3.result, b=0.0)
brain_convolution_raw = float_math(operation=ADD, a=brain_sin1.result, b=brain_sin2.result)
brain_convolution = float_math(operation=ADD, a=brain_convolution_raw.result, b=brain_sin3.result)

# Scale convolution amplitude (very subtle)
brain_convolution_scaled = float_math(operation=MULTIPLY, a=brain_convolution.result, b=brain_convolution_depth.result)

# === Face region (bottom half, ny < 0) ===
face_negate = float_math(operation=NEGATE, a=ny.result)
face_gate = float_math(operation=MAXIMUM, a=face_negate.result, b=0.0)

# Face depth displacement
face_x_gate_raw = float_math(operation=SUBTRACT, a=0.4, b=float_math(operation=ABSOLUTE, a=nx.result))
face_x_gate = float_math(operation=MAXIMUM, a=face_x_gate_raw.result, b=0.0)
face_z_gate_raw = float_math(operation=SUBTRACT, a=0.35, b=float_math(operation=ABSOLUTE, a=nz.result))
face_z_gate = float_math(operation=MAXIMUM, a=face_z_gate_raw.result, b=0.0)
face_region = float_math(operation=MULTIPLY, a=face_x_gate.result, b=face_z_gate.result)

face_y_abs = float_math(operation=ABSOLUTE, a=ny.result)
face_y_profile_raw = float_math(operation=SUBTRACT, a=0.5, b=face_y_abs.result)
face_y_profile = float_math(operation=MAXIMUM, a=face_y_profile_raw.result, b=0.0)
face_depth_profile = float_math(operation=MULTIPLY, a=face_region.result, b=face_y_profile.result)

# === Combine displacements (very subtle) ===
brain_disp = float_math(operation=MULTIPLY, a=brain_gate_raw.result, b=brain_convolution_scaled.result)
face_disp = float_math(operation=MULTIPLY, a=face_gate.result, b=face_depth_profile.result)
face_disp_scaled = float_math(operation=MULTIPLY, a=face_disp.result, b=face_depth.result)

total_disp_raw = float_math(operation=ADD, a=brain_disp.result, b=face_disp_scaled.result)

# Apply displacement along normal
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=total_disp_raw.result)
disp_y = float_math(operation=MULTIPLY, a=nrm_xyz.y, b=total_disp_raw.result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=total_disp_raw.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)

shaped = set_position(geometry=scaled_sphere.geometry, offset=displacement.vector)

# ── Position to match reference centroid ──────────────────────────────
brorogan_base = transform_geometry(
    geometry=shaped.geometry,
    translation=<1.1756, -1.5140, -0.2690>,
    scale=<1.0, 1.0, 1.0>
)

# ── Tag for visualization/metrics ─────────────────────────────────────
brorogan = tag_geometry(geometry=brorogan_base.geometry, tags="brorogan,head,brain")
