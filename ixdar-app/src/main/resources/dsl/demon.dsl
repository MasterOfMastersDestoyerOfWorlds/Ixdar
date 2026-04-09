# Demon — Procedural recreation via primitives, displacement, and sweep
# Reference: /Users/acw28/Blends/exports/Demon.obj (612K verts, 1.3M faces)
# Dimensions: X=70.0, Y=56.0, Z=79.6, centroid=(3.5, -6.6, -9.6)
# Strategy: Build skull + body blockout, use displacement for surface detail,
#           curve_sweep for twisted horns, tag regions for quality metrics.

# ── Parameters ────────────────────────────────────────────────────────
subdivisions = input_int(name="subdivisions", default=8, min=4, max=12)
horn_segments = input_int(name="horn_segments", default=32, min=16, max=64)

# ── Skull Base ─────────────────────────────────────────────────────────

# Start with a UV sphere for the cranium
skull_sphere = uv_sphere(radius=10.0, segments=64, rings=48)

# Scale to match skull proportions (from reference: ~35 wide, ~40 tall, ~30 deep)
skull_base = transform_geometry(
    geometry=skull_sphere.mesh,
    translation=<3.5, -6.6, -9.6>,
    scale=<1.0, 1.2, 0.85>
)

# ── Displacement: Shape skull features ──────────────────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
nrm = input_normal()

# Normalize coordinates relative to skull center
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=10.0)
ny_off = float_math(operation=SUBTRACT, a=pos_xyz.y, b=-6.6)
ny = float_math(operation=DIVIDE, a=ny_off.result, b=12.0)
nz_off = float_math(operation=SUBTRACT, a=pos_xyz.z, b=-9.6)
nz = float_math(operation=DIVIDE, a=nz_off.result, b=8.5)

# --- Jaw extension (mandible) ---
jaw_y_gate_raw = float_math(operation=SUBTRACT, a=-4.0, b=ny.result)
jaw_y_gate = float_math(operation=MAXIMUM, a=jaw_y_gate_raw.result, b=0.0)
jaw_nx_abs = float_math(operation=ABSOLUTE, a=nx.result)
jaw_x_taper = float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=MULTIPLY, a=jaw_nx_abs.result, b=0.5).result)
jaw_x_taper_c = float_math(operation=MAXIMUM, a=jaw_x_taper.result, b=0.0)
jaw_push = float_math(operation=MULTIPLY, a=jaw_y_gate.result, b=jaw_x_taper_c.result)
jaw_push_scaled = float_math(operation=MULTIPLY, a=jaw_push.result, b=-2.5)

# --- Eye sockets (hollows) ---
eye_y_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.5, b=float_math(operation=ABSOLUTE, a=ny.result).result).result, b=0.0)
eye_x_dist = float_math(operation=ABSOLUTE, a=float_math(operation=SUBTRACT, a=nx.result, b=0.0).result)
eye_x_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.6, b=eye_x_dist.result).result, b=0.0)
eye_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
eye_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.8, b=eye_z_front.result).result, b=0.0)
eye_mask = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=eye_y_gate.result, b=eye_x_gate.result).result, b=eye_z_gate.result)
eye_push = float_math(operation=MULTIPLY, a=eye_mask.result, b=-3.5)

# --- Brow ridge ---
brow_y_gate_raw = float_math(operation=SUBTRACT, a=ny.result, b=0.3)
brow_y_gate = float_math(operation=MAXIMUM, a=brow_y_gate_raw.result, b=0.0)
brow_z_front = float_math(operation=MAXIMUM, a=nz.result, b=0.0)
brow_push = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=brow_y_gate.result, b=brow_z_front.result).result, b=1.2)

# --- Temporal narrowing ---
temple_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.15)
temple_y_dist = float_math(operation=ABSOLUTE, a=temple_y_center.result)
temple_y_gate_raw = float_math(operation=SUBTRACT, a=0.35, b=temple_y_dist.result)
temple_y_gate = float_math(operation=MAXIMUM, a=temple_y_gate_raw.result, b=0.0)
temple_x_abs = float_math(operation=ABSOLUTE, a=nx.result)
temple_x_gate_raw = float_math(operation=SUBTRACT, a=0.7, b=temple_x_abs.result)
temple_x_gate = float_math(operation=MAXIMUM, a=temple_x_gate_raw.result, b=0.0)
temple_push = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=temple_y_gate.result, b=temple_x_gate.result).result, b=-1.5)

# --- Back skull flattening ---
back_gate_raw = float_math(operation=SUBTRACT, a=-0.6, b=nz.result)
back_gate = float_math(operation=MAXIMUM, a=back_gate_raw.result, b=0.0)
back_push = float_math(operation=MULTIPLY, a=back_gate.result, b=0.8)

# --- Combine displacement ---
f1 = float_math(operation=ADD, a=back_push.result, b=temple_push.result)
f2 = float_math(operation=ADD, a=f1.result, b=eye_push.result)
f3 = float_math(operation=ADD, a=f2.result, b=brow_push.result)
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=f3.result)
disp_y = float_math(operation=ADD, a=jaw_push_scaled.result, b=float_math(operation=MULTIPLY, a=nrm_xyz.y, b=f3.result).result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=f3.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)

skull_shaped = set_position(geometry=skull_base.geometry, offset=displacement.vector)

# --- Eye socket carving ---
left_eye_ball = uv_sphere(radius=1.8, segments=16, rings=12)
right_eye_ball = uv_sphere(radius=1.8, segments=16, rings=12)
left_eye_pos = transform_geometry(geometry=left_eye_ball.mesh, translation=<-3.2, 1.5, 5.5>)
right_eye_pos = transform_geometry(geometry=right_eye_ball.mesh, translation=<3.2, 1.5, 5.5>)
skull_minus_left = mesh_boolean(mesh_a=skull_shaped.geometry, mesh_b=left_eye_pos.geometry, operation=DIFFERENCE)
skull_minus_eyes = mesh_boolean(mesh_a=skull_minus_left.geometry, mesh_b=right_eye_pos.geometry, operation=DIFFERENCE)

# --- Nasal cavity ---
nose_ball = uv_sphere(radius=1.0, segments=12, rings=10)
nose_shape = transform_geometry(geometry=nose_ball.mesh, translation=<0.0, -2.5, 6.5>, scale=<0.6, 1.2, 0.8>)
skull_carved = mesh_boolean(mesh_a=skull_minus_eyes.geometry, mesh_b=nose_shape.geometry, operation=DIFFERENCE)

# --- Teeth/jaw opening ---
jaw_ball = quad_cylinder(radius=2.5, height=3.0, segments=16, rings=1, cap_rings=1)
jaw_pos = transform_geometry(geometry=jaw_ball.mesh, translation=<0.0, -5.5, 4.0>, scale=<1.0, 0.8, 1.2>)
skull_final = mesh_boolean(mesh_a=skull_carved.geometry, mesh_b=jaw_pos.geometry, operation=DIFFERENCE)

# ── Body Base ──────────────────────────────────────────────────────────
# Torso: quad_cylinder for better proportion control
body_base = quad_cylinder(radius=16.0, height=40.0, segments=32, rings=4, cap_rings=2)
body_scaled = transform_geometry(
    geometry=body_base.mesh,
    translation=<3.5, -70.0, -9.6>,
    scale=<2.0, 1.0, 2.2>
)

# ── Body Displacement: Ribcage and muscle texture ──────────────────────
pos2 = input_position()
pos2_xyz = separate_xyz(vector=pos2.vector)

# Normalize for body
nx2 = float_math(operation=DIVIDE, a=pos2_xyz.x, b=12.0)
ny2_off = float_math(operation=SUBTRACT, a=pos2_xyz.y, b=-60.0)
ny2 = float_math(operation=DIVIDE, a=ny2_off.result, b=20.0)
nz2_off = float_math(operation=SUBTRACT, a=pos2_xyz.z, b=-9.6)
nz2 = float_math(operation=DIVIDE, a=nz2_off.result, b=10.0)

# --- Ribcage pattern: horizontal bands with gaps ---
rib_y_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.5, b=float_math(operation=ABSOLUTE, a=ny2.result).result).result, b=0.0)
rib_phase = float_math(operation=MODULO, a=float_math(operation=ADD, a=ny2.result, b=0.0).result, b=0.3)
rib_band = float_math(operation=SUBTRACT, a=0.15, b=float_math(operation=ABSOLUTE, a=rib_phase.result).result)
rib_band_c = float_math(operation=MAXIMUM, a=rib_band.result, b=0.0)
rib_mask = float_math(operation=MULTIPLY, a=rib_y_gate.result, b=rib_band_c.result)
rib_push = float_math(operation=MULTIPLY, a=rib_mask.result, b=1.8)

# --- Muscle definition: vertical striations ---
muscle_x_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.7, b=float_math(operation=ABSOLUTE, a=nx2.result).result).result, b=0.0)
muscle_phase = float_math(operation=MODULO, a=float_math(operation=ADD, a=ny2.result, b=0.5).result, b=0.4)
muscle_stripe = float_math(operation=SUBTRACT, a=0.2, b=float_math(operation=ABSOLUTE, a=muscle_phase.result).result)
muscle_stripe_c = float_math(operation=MAXIMUM, a=muscle_stripe.result, b=0.0)
muscle_mask = float_math(operation=MULTIPLY, a=muscle_x_gate.result, b=muscle_stripe_c.result)
muscle_push = float_math(operation=MULTIPLY, a=muscle_mask.result, b=1.2)

# --- Overall body taper (narrower at waist) ---
waist_gate_raw = float_math(operation=SUBTRACT, a=0.3, b=float_math(operation=ABSOLUTE, a=ny2.result).result)
waist_gate = float_math(operation=MAXIMUM, a=waist_gate_raw.result, b=0.0)
waist_taper = float_math(operation=MULTIPLY, a=waist_gate.result, b=-0.3)

# --- Combine body displacement ---
body_disp_x = float_math(operation=MULTIPLY, a=nx2.result, b=float_math(operation=ADD, a=rib_push.result, b=muscle_push.result).result)
body_disp_y = float_math(operation=MULTIPLY, a=float_math(operation=ADD, a=rib_push.result, b=muscle_push.result).result, b=ny2.result)
body_disp_z = float_math(operation=MULTIPLY, a=nz2.result, b=float_math(operation=ADD, a=rib_push.result, b=muscle_push.result).result)
body_displacement = combine_xyz(x=body_disp_x.result, y=body_disp_y.result, z=body_disp_z.result)

body_shaped = set_position(geometry=body_scaled.geometry, offset=body_displacement.vector)

# ── Arms ──────────────────────────────────────────────────────────────
# Left arm (raised): cylinder with rotation
left_arm = transform_geometry(
    geometry=quad_cylinder(radius=1.8, height=25.0, segments=16, rings=2, cap_rings=1).mesh,
    translation=<-8.0, -35.0, -5.0>,
    rotation=<0.0, 0.0, 1.2>,
    scale=<1.0, 1.0, 1.0>
)

# Right arm (lower): cylinder with different rotation
right_arm = transform_geometry(
    geometry=quad_cylinder(radius=2.0, height=28.0, segments=16, rings=2, cap_rings=1).mesh,
    translation=<15.0, -45.0, -8.0>,
    rotation=<0.0, 0.0, -0.5>,
    scale=<1.0, 1.0, 1.0>
)

# ── Hands ─────────────────────────────────────────────────────────────
# Left hand (raised near face): small sphere/capsule
left_hand = transform_geometry(
    geometry=uv_sphere(radius=2.5, segments=16, rings=12).mesh,
    translation=<-10.0, -15.0, 0.0>,
    scale=<1.2, 1.0, 1.2>
)

# Right hand (lower): small sphere/capsule
right_hand = transform_geometry(
    geometry=uv_sphere(radius=2.5, segments=16, rings=12).mesh,
    translation=<18.0, -70.0, -12.0>,
    scale=<1.2, 1.0, 1.2>
)

# ── Horns: Curve sweep for twisted shape ───────────────────────────────
# Horn curve A (left horn)
horn_a_p1 = curve_bezier(
    resolution=horn_segments,
    start=<-6.0, 25.0, 3.0>,
    handle_start=<-8.0, 30.0, 2.0>,
    end=<-10.0, 45.0, 1.0>,
    handle_end=<-12.0, 50.0, 0.0>,
    mode=CUBIC
)

# Horn curve B (right horn, mirrored)
horn_b_p1 = curve_bezier(
    resolution=horn_segments,
    start=<6.0, 25.0, 3.0>,
    handle_start=<8.0, 30.0, 2.0>,
    end=<10.0, 45.0, 1.0>,
    handle_end=<12.0, 50.0, 0.0>,
    mode=CUBIC
)

# Horn cross-section (oval) - use mesh_grid as profile
horn_profile = mesh_grid(u_tiles=8, v_tiles=1, u_tile_size=0.15, v_tile_size=0.25)

# Sweep horns
left_horn = curve_sweep(curve=horn_a_p1.curve, profile=horn_profile.mesh, caps=true)
right_horn = curve_sweep(curve=horn_b_p1.curve, profile=horn_profile.mesh, caps=true)

# ── Assembly ──────────────────────────────────────────────────────────
# Join all body parts
body_j1 = join_geometry(a=body_shaped.geometry, b=left_arm.geometry)
body_j2 = join_geometry(a=body_j1.geometry, b=right_arm.geometry)
body_j3 = join_geometry(a=body_j2.geometry, b=left_hand.geometry)
body_j4 = join_geometry(a=body_j3.geometry, b=right_hand.geometry)

# Add skull
head_body = join_geometry(a=skull_final.geometry, b=body_j4.geometry)

# Add horns
head_with_horns = join_geometry(a=head_body.geometry, b=left_horn)
demon_final = join_geometry(a=head_with_horns.geometry, b=right_horn)

# ── Tag regions for quality metrics ───────────────────────────────────
demon_tagged = tag_geometry(
    geometry=demon_final.geometry,
    tags="demon,head,skull,body,arms,hands,horns"
)
