# Skull — Procedural recreation via displacement + boolean carving
# Reference: /Users/acw28/Blends/exports/Skull.obj (497K verts)
# Dimensions: X=2.12, Y=2.95, Z=2.77, centroid=(0, -0.12, 0.17)

# ── Parameters ────────────────────────────────────────────────────────
subdivisions = input_int(name="subdivisions", default=6, min=4, max=8)

# ── Base cranium: UV sphere stretched to ellipsoid ────────────────────
# Cranium roughly semi-axes: X=1.05, Y=1.3 (taller), Z=1.1
base = uv_sphere(radius=1.0, segments=64, rings=48)
cranium = transform_geometry(geometry=base.mesh, translation=<0.0, -0.12, 0.0>, scale=<1.05, 1.35, 1.2>)

# ── Displacement: shape the skull profile ─────────────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
nrm = input_normal()

# Normalize coordinates to [-1, 1] range relative to skull center
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=1.1)
ny_off = float_math(operation=ADD, a=pos_xyz.y, b=0.12)
ny = float_math(operation=DIVIDE, a=ny_off.result, b=1.4)
nz_off = float_math(operation=SUBTRACT, a=pos_xyz.z, b=0.17)
nz = float_math(operation=DIVIDE, a=nz_off.result, b=1.3)

# --- Jaw extension: push downward for vertices below Y=-0.5 ---
jaw_gate_raw = float_math(operation=SUBTRACT, a=-0.5, b=ny.result)
jaw_gate = float_math(operation=MAXIMUM, a=jaw_gate_raw.result, b=0.0)
# Jaw narrows in X and tapers in Z (front-heavy)
jaw_nx_abs = float_math(operation=ABSOLUTE, a=nx.result)
jaw_nx_scaled = float_math(operation=MULTIPLY, a=jaw_nx_abs.result, b=0.6)
jaw_x_taper = float_math(operation=SUBTRACT, a=1.0, b=jaw_nx_scaled.result)
jaw_x_taper_c = float_math(operation=MAXIMUM, a=jaw_x_taper.result, b=0.0)
jaw_nz_scaled = float_math(operation=MULTIPLY, a=nz.result, b=0.3)
jaw_z_bias = float_math(operation=ADD, a=0.5, b=jaw_nz_scaled.result)
jaw_z_bias_c = float_math(operation=MAXIMUM, a=jaw_z_bias.result, b=0.0)
jaw_xz = float_math(operation=MULTIPLY, a=jaw_x_taper_c.result, b=jaw_z_bias_c.result)
jaw_push = float_math(operation=MULTIPLY, a=jaw_gate.result, b=jaw_xz.result)
jaw_push_scaled = float_math(operation=MULTIPLY, a=jaw_push.result, b=-0.8)

# --- Brow ridge: slight forward bulge above eyes ---
brow_y_gate_raw = float_math(operation=SUBTRACT, a=ny.result, b=0.15)
brow_y_gate = float_math(operation=MAXIMUM, a=brow_y_gate_raw.result, b=0.0)
brow_y_upper = float_math(operation=SUBTRACT, a=0.35, b=ny.result)
brow_y_upper_c = float_math(operation=MAXIMUM, a=brow_y_upper.result, b=0.0)
brow_band = float_math(operation=MULTIPLY, a=brow_y_gate.result, b=brow_y_upper_c.result)
brow_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
brow_zs = float_math(operation=MULTIPLY, a=brow_z_front.result, b=0.15)
brow_push = float_math(operation=MULTIPLY, a=brow_band.result, b=brow_zs.result)

# --- Cheekbone prominence: lateral bulge at mid-face ---
cheek_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.1)
cheek_y_dist = float_math(operation=ABSOLUTE, a=cheek_y_center.result)
cheek_y_gate_raw = float_math(operation=SUBTRACT, a=0.25, b=cheek_y_dist.result)
cheek_y_gate = float_math(operation=MAXIMUM, a=cheek_y_gate_raw.result, b=0.0)
cheek_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
cheek_x_gate_raw = float_math(operation=SUBTRACT, a=cheek_x_abs.result, b=0.3)
cheek_x_gate = float_math(operation=MAXIMUM, a=cheek_x_gate_raw.result, b=0.0)
cheek_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
cheek_zs = float_math(operation=MULTIPLY, a=cheek_z_front.result, b=0.2)
cheek_xz = float_math(operation=MULTIPLY, a=cheek_x_gate.result, b=cheek_zs.result)
cheek_push = float_math(operation=MULTIPLY, a=cheek_y_gate.result, b=cheek_xz.result)

# --- Back of skull: flatten slightly ---
back_gate_raw = float_math(operation=SUBTRACT, a=-0.5, b=nz.result)
back_gate = float_math(operation=MAXIMUM, a=back_gate_raw.result, b=0.0)
back_push = float_math(operation=MULTIPLY, a=back_gate.result, b=0.1)

# --- Temporal narrowing: skull narrows at temples ---
temple_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.1)
temple_y_dist = float_math(operation=ABSOLUTE, a=temple_y_center.result)
temple_y_gate_raw = float_math(operation=SUBTRACT, a=0.3, b=temple_y_dist.result)
temple_y_gate = float_math(operation=MAXIMUM, a=temple_y_gate_raw.result, b=0.0)
temple_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
temple_x_gate_raw = float_math(operation=SUBTRACT, a=temple_x_abs.result, b=0.5)
temple_x_gate = float_math(operation=MAXIMUM, a=temple_x_gate_raw.result, b=0.0)
temple_xs = float_math(operation=MULTIPLY, a=temple_x_gate.result, b=-0.15)
temple_push = float_math(operation=MULTIPLY, a=temple_y_gate.result, b=temple_xs.result)

# --- Combine displacement as Y offset (jaw) + normal offset (features) ---
f1 = float_math(operation=ADD, a=back_push.result, b=temple_push.result)
f2 = float_math(operation=ADD, a=cheek_push.result, b=f1.result)
feature_disp = float_math(operation=ADD, a=brow_push.result, b=f2.result)
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=feature_disp.result)
nrm_y_disp = float_math(operation=MULTIPLY, a=nrm_xyz.y, b=feature_disp.result)
disp_y = float_math(operation=ADD, a=jaw_push_scaled.result, b=nrm_y_disp.result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=feature_disp.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
shaped_skull = set_position(geometry=cranium.geometry, offset=displacement.vector)

# ── Boolean carving: eye sockets ──────────────────────────────────────
eye_ball = uv_sphere(radius=0.28, segments=24, rings=16)
left_eye = transform_geometry(geometry=eye_ball.mesh, translation=<-0.38, 0.0, 0.95>)
right_eye = transform_geometry(geometry=eye_ball.mesh, translation=<0.38, 0.0, 0.95>)
skull_minus_left = mesh_boolean(mesh_a=shaped_skull.geometry, mesh_b=left_eye.geometry, operation=DIFFERENCE)
skull_minus_eyes = mesh_boolean(mesh_a=skull_minus_left.geometry, mesh_b=right_eye.geometry, operation=DIFFERENCE)

# ── Boolean carving: nasal cavity ─────────────────────────────────────
nose_ball = uv_sphere(radius=0.15, segments=16, rings=12)
nose_shape = transform_geometry(geometry=nose_ball.mesh, translation=<0.0, -0.25, 1.0>, scale=<0.7, 1.3, 1.0>)
skull_carved = mesh_boolean(mesh_a=skull_minus_eyes.geometry, mesh_b=nose_shape.geometry, operation=DIFFERENCE)

# ── Final output ──────────────────────────────────────────────────────
# Note: solidify_mesh requires quad meshes; boolean ops produce triangles.
# The carved skull is already a closed solid from the boolean operations.
