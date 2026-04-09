# Woman Face — Procedural approximation using UV sphere base with displacement shaping
# Reference: /Users/acw28/Blends/exports/Woman_Face.obj (513K verts, 1M faces)
# Dimensions: X=0.295, Y=0.331, Z=0.215, centroid=(-0.037, 0.100, -0.063)
# Focus: silhouette match and major proportions, not ultra-high-poly detail

# ── Parameters ──────────────────────────────────────────────────────────
subdivisions = input_int(name="subdivisions", default=5, min=4, max=7)

# ── Base cranium: UV sphere stretched to approximate face proportions ───
# Face dimensions ratio: X:Y:Z ≈ 0.295:0.331:0.215 ≈ 1.0:1.12:0.73
# Start with radius 0.15 sphere, stretch to match bounding box
base = uv_sphere(radius=0.15, segments=64, rings=48)
cranium = transform_geometry(geometry=base.mesh, translation=<-0.037, 0.100, -0.063>, scale=<1.0, 1.12, 0.73>)

# ── Position input for displacement ────────────────────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
nrm = input_normal()

# Normalize coordinates relative to face center for displacement calculation
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=0.15)
ny_off = float_math(operation=SUBTRACT, a=pos_xyz.y, b=0.100)
ny = float_math(operation=DIVIDE, a=ny_off.result, b=0.17)
nz_off = float_math(operation=SUBTRACT, a=pos_xyz.z, b=-0.063)
nz = float_math(operation=DIVIDE, a=nz_off.result, b=0.11)

# ── Forehead: rounded bulge at top-front ───────────────────────────────
# Forehead extends forward and up from cranium
forehead_y_gate_raw = float_math(operation=SUBTRACT, a=ny.result, b=-0.3)
forehead_y_gate = float_math(operation=MAXIMUM, a=forehead_y_gate_raw.result, b=0.0)
forehead_y_upper = float_math(operation=SUBTRACT, a=0.8, b=ny.result)
forehead_y_upper_c = float_math(operation=MAXIMUM, a=forehead_y_upper.result, b=0.0)
forehead_band = float_math(operation=MULTIPLY, a=forehead_y_gate.result, b=forehead_y_upper_c.result)
# Forehead bulges forward (positive Z) and slightly outward in X
forehead_z_push = float_math(operation=MULTIPLY, a=forehead_band.result, b=0.05)
forehead_x_out = float_math(operation=MULTIPLY, a=forehead_band.result, b=0.02)

# ── Cheekbones: lateral prominence at mid-face ─────────────────────────
# Cheekbones are prominent at Y around 0.0 to 0.05, extend outward in X
cheek_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.05)
cheek_y_dist = float_math(operation=ABSOLUTE, a=cheek_y_center.result)
cheek_y_gate_raw = float_math(operation=SUBTRACT, a=0.25, b=cheek_y_dist.result)
cheek_y_gate = float_math(operation=MAXIMUM, a=cheek_y_gate_raw.result, b=0.0)
# Cheek prominence peaks at mid-X, tapers toward edges
cheek_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
cheek_x_peak = float_math(operation=SUBTRACT, a=0.5, b=cheek_x_abs.result)
cheek_x_peak_c = float_math(operation=MAXIMUM, a=cheek_x_peak.result, b=0.0)
cheek_x_peak_sq = float_math(operation=MULTIPLY, a=cheek_x_peak_c.result, b=cheek_x_peak_c.result)
cheek_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
cheek_zs = float_math(operation=MULTIPLY, a=cheek_z_front.result, b=0.5)
cheek_xz = float_math(operation=MULTIPLY, a=cheek_x_peak_sq.result, b=cheek_zs.result)
cheek_push = float_math(operation=MULTIPLY, a=cheek_y_gate.result, b=cheek_xz.result)

# ── Jaw: curves inward at bottom, extends forward ──────────────────────
# Jaw region is below Y=-0.1, narrows toward chin
jaw_y_gate_raw = float_math(operation=SUBTRACT, a=-0.15, b=ny.result)
jaw_y_gate = float_math(operation=MAXIMUM, a=jaw_y_gate_raw.result, b=0.0)
# Jaw narrows in X (smaller width at bottom)
jaw_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
jaw_x_taper = float_math(operation=SUBTRACT, a=0.6, b=jaw_x_abs.result)
jaw_x_taper_c = float_math(operation=MAXIMUM, a=jaw_x_taper.result, b=0.0)
jaw_x_taper_sq = float_math(operation=MULTIPLY, a=jaw_x_taper_c.result, b=jaw_x_taper_c.result)
# Jaw extends forward slightly
jaw_z_push = float_math(operation=MULTIPLY, a=jaw_y_gate.result, b=jaw_x_taper_sq.result)
jaw_z_push_scaled = float_math(operation=MULTIPLY, a=jaw_z_push.result, b=0.03)

# ── Eye sockets: recessed areas around Y=0.0 ───────────────────────────
# Eye sockets are indentations, so negative displacement
eye_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.02)
eye_y_dist = float_math(operation=ABSOLUTE, a=eye_y_center.result)
eye_y_gate_raw = float_math(operation=SUBTRACT, a=0.25, b=eye_y_dist.result)
eye_y_gate = float_math(operation=MAXIMUM, a=eye_y_gate_raw.result, b=0.0)
# Eye sockets are at lateral X positions (~±0.35 normalized)
eye_x_center = float_math(operation=SUBTRACT, a=nx.result, b=0.0)
eye_x_dist_l = float_math(operation=ABSOLUTE, a=float_math(operation=ADD, a=nx.result, b=0.35))
eye_x_dist_r = float_math(operation=ABSOLUTE, a=float_math(operation=SUBTRACT, a=nx.result, b=0.35))
eye_x_gate_l = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.15, b=eye_x_dist_l.result), b=0.0)
eye_x_gate_r = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.15, b=eye_x_dist_r.result), b=0.0)
eye_x_gate = float_math(operation=ADD, a=eye_x_gate_l.result, b=eye_x_gate_r.result)
eye_x_gate_c = float_math(operation=MAXIMUM, a=eye_x_gate.result, b=0.0)
eye_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
eye_zs = float_math(operation=MULTIPLY, a=eye_z_front.result, b=0.6)
eye_xz = float_math(operation=MULTIPLY, a=eye_y_gate.result, b=eye_x_gate_c.result)
eye_recess = float_math(operation=MULTIPLY, a=eye_xz.result, b=-0.04)

# ── Nose: central protrusion at front ──────────────────────────────────
# Nose is centered at X=0, extends forward from face plane
nose_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.1)
nose_y_dist = float_math(operation=ABSOLUTE, a=nose_y_center.result)
nose_y_gate_raw = float_math(operation=SUBTRACT, a=0.4, b=nose_y_dist.result)
nose_y_gate = float_math(operation=MAXIMUM, a=nose_y_gate_raw.result, b=0.0)
# Nose prominence at center X, circular profile
nose_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
nose_x_gate_raw = float_math(operation=SUBTRACT, a=0.25, b=nose_x_abs.result)
nose_x_gate = float_math(operation=MAXIMUM, a=nose_x_gate_raw.result, b=0.0)
nose_x_gate_c = float_math(operation=MAXIMUM, a=nose_x_gate.result, b=0.0)
nose_x_gate_sq = float_math(operation=MULTIPLY, a=nose_x_gate_c.result, b=nose_x_gate_c.result)
# Nose extends forward (positive Z)
nose_z_push = float_math(operation=MULTIPLY, a=nose_y_gate.result, b=nose_x_gate_sq.result)
nose_push = float_math(operation=MULTIPLY, a=nose_z_push.result, b=0.06)

# ── Mouth/lower face: subtle curve below nose ──────────────────────────
mouth_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.25)
mouth_y_dist = float_math(operation=ABSOLUTE, a=mouth_y_center.result)
mouth_y_gate_raw = float_math(operation=SUBTRACT, a=0.2, b=mouth_y_dist.result)
mouth_y_gate = float_math(operation=MAXIMUM, a=mouth_y_gate_raw.result, b=0.0)
mouth_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
mouth_x_gate_raw = float_math(operation=SUBTRACT, a=0.4, b=mouth_x_abs.result)
mouth_x_gate = float_math(operation=MAXIMUM, a=mouth_x_gate_raw.result, b=0.0)
mouth_x_gate_c = float_math(operation=MAXIMUM, a=mouth_x_gate.result, b=0.0)
mouth_z_push = float_math(operation=MULTIPLY, a=mouth_y_gate.result, b=mouth_x_gate_c.result)
mouth_push = float_math(operation=MULTIPLY, a=mouth_z_push.result, b=0.02)

# ── Chin: rounded protrusion at bottom ─────────────────────────────────
chin_y_gate_raw = float_math(operation=SUBTRACT, a=-0.35, b=ny.result)
chin_y_gate = float_math(operation=MAXIMUM, a=chin_y_gate_raw.result, b=0.0)
chin_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
chin_x_gate_raw = float_math(operation=SUBTRACT, a=0.5, b=chin_x_abs.result)
chin_x_gate = float_math(operation=MAXIMUM, a=chin_x_gate_raw.result, b=0.0)
chin_x_gate_c = float_math(operation=MAXIMUM, a=chin_x_gate.result, b=0.0)
chin_z_push = float_math(operation=MULTIPLY, a=chin_y_gate.result, b=chin_x_gate_c.result)
chin_push = float_math(operation=MULTIPLY, a=chin_z_push.result, b=0.03)

# ── Combine all displacement components ────────────────────────────────
# Feature displacement (applied along normal for cheek, nose, forehead, chin)
f1 = float_math(operation=ADD, a=forehead_z_push.result, b=forehead_x_out.result)
f2 = float_math(operation=ADD, a=cheek_push.result, b=f1.result)
f3 = float_math(operation=ADD, a=nose_push.result, b=f2.result)
f4 = float_math(operation=ADD, a=mouth_push.result, b=f3.result)
feature_disp = float_math(operation=ADD, a=chin_push.result, b=f4.result)

# Final displacement: feature displacement along normal + jaw extension + eye recess
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=feature_disp.result)
disp_y = float_math(operation=MULTIPLY, a=nrm_xyz.y, b=feature_disp.result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=feature_disp.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)

# Apply displacement to cranium
shaped_face = set_position(geometry=cranium.geometry, offset=displacement.vector)

# ── Eye sockets: boolean carving ───────────────────────────────────────
# Create sphere-based eye cavities
left_eye_ball = uv_sphere(radius=0.04, segments=16, rings=12)
right_eye_ball = uv_sphere(radius=0.04, segments=16, rings=12)
left_eye = transform_geometry(geometry=left_eye_ball.mesh, translation=<-0.06, 0.0, -0.15>)
right_eye = transform_geometry(geometry=right_eye_ball.mesh, translation=<0.06, 0.0, -0.15>)
face_minus_left_eye = mesh_boolean(mesh_a=shaped_face.geometry, mesh_b=left_eye.geometry, operation=DIFFERENCE)
face_minus_eyes = mesh_boolean(mesh_a=face_minus_left_eye.geometry, mesh_b=right_eye.geometry, operation=DIFFERENCE)

# ── Nose cavity: subtle boolean refinement ─────────────────────────────
nose_cavity = uv_sphere(radius=0.025, segments=12, rings=10)
nose_cavity_tf = transform_geometry(geometry=nose_cavity.mesh, translation=<0.0, -0.08, -0.135>, scale=<0.8, 0.6, 1.2>)
face_carved = mesh_boolean(mesh_a=face_minus_eyes.geometry, mesh_b=nose_cavity_tf.geometry, operation=DIFFERENCE)

# ── Tag regions for quality metrics ────────────────────────────────────
face_tagged = tag_geometry(geometry=face_carved.geometry, tags="face,cranium,eyes,nose,mouth")
