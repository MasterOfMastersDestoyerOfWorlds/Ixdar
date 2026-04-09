# 3D ScanStore Free+Head — Procedural recreation via mesh primitives
# Reference: /Users/acw28/Blends/exports/3D_ScanStore_Free+Head.obj
# Original stats: 202,093 verts, 154,233 faces, 10 mesh objects
# Dimensions: X=23.5, Y=34.0, Z=21.9, centroid=(0, 22.7, 3.8)
# Strategy: sphere-based head + cylinder neck + ellipsoid shoulders + ears + facial features

# ── Parameters ─────────────────────────────────────────────────────────
head_subdivisions = input_int(name="subdivisions", default=64, min=32, max=128)
feature_subdivisions = input_int(name="feature_subdivisions", default=32, min=16, max=64)

# ── Base Head (Cranium) ───────────────────────────────────────────────
# Head spans roughly Y=[5.7, 40], X=[-11.8, 11.7], Z=[-7.2, 14.8]
# Center at Y=22.7, radius ~11.75 in X/Z, height ~17 in Y
# Scale sphere to match: X=11.75, Y=17, Z=11
head_base = uv_sphere(radius=1.0, segments=64, rings=48)
head_cranium = transform_geometry(geometry=head_base.mesh, translation=<0.0, 22.7, 3.8>, scale=<11.75, 17.0, 11.0>)

# ── Neck ──────────────────────────────────────────────────────────────
# Neck extends from chin (~Y=5.7) down to base
# Width ~6-8, height ~12
neck_base = cylinder(radius=1.0, height=1.0, segments=32)
neck = transform_geometry(geometry=neck_base.mesh, translation=<0.0, 10.0, 3.8>, scale=<3.5, 12.0, 3.5>)

# ── Shoulders ─────────────────────────────────────────────────────────
# Shoulders extend from base, wider than head
# Left shoulder: X~[-18, -8], Right shoulder: X~[8, 18]
shoulder_left = transform_geometry(geometry=uv_sphere(radius=1.0, segments=32, rings=24).mesh, 
                                     translation=<-13.0, 2.0, 3.8>, 
                                     scale=<6.0, 4.0, 5.0>)
shoulder_right = transform_geometry(geometry=uv_sphere(radius=1.0, segments=32, rings=24).mesh, 
                                     translation=<13.0, 2.0, 3.8>, 
                                     scale=<6.0, 4.0, 5.0>)

# ── Ears ──────────────────────────────────────────────────────────────
# Ears on sides of head at Y~20, Z~0
# Left ear: X~-10, Right ear: X~10
ear_left = transform_geometry(geometry=cylinder(radius=0.8, height=1.0, segments=24).mesh, 
                              translation=<-10.5, 20.0, 3.8>, 
                              scale=<1.5, 4.0, 1.5>,
                              rotation=<0.0, 0.0, 0.3>)
ear_right = transform_geometry(geometry=cylinder(radius=0.8, height=1.0, segments=24).mesh, 
                               translation=<10.5, 20.0, 3.8>, 
                               scale=<1.5, 4.0, 1.5>,
                               rotation=<0.0, 0.0, -0.3>)

# ── Assemble Head + Neck + Shoulders + Ears ───────────────────────────
head_neck = join_geometry(a=head_cranium.geometry, b=neck.geometry)
head_neck_shoulders = join_geometry(a=head_neck.geometry, b=shoulder_left.geometry)
head_neck_shoulders_ears = join_geometry(a=head_neck_shoulders.geometry, b=shoulder_right.geometry)
head_neck_shoulders_ears_final = join_geometry(a=head_neck_shoulders_ears.geometry, b=ear_left.geometry)
head_assembly = join_geometry(a=head_neck_shoulders_ears_final.geometry, b=ear_right.geometry)

# ── Position inputs for displacement ──────────────────────────────────
pos = input_position()
pos_xyz = separate_xyz(vector=pos.vector)
nrm = input_normal()

# Normalize coordinates relative to head center
nx = float_math(operation=DIVIDE, a=pos_xyz.x, b=11.75)
ny_off = float_math(operation=SUBTRACT, a=pos_xyz.y, b=22.7)
ny = float_math(operation=DIVIDE, a=ny_off.result, b=17.0)
nz_off = float_math(operation=SUBTRACT, a=pos_xyz.z, b=3.8)
nz = float_math(operation=DIVIDE, a=nz_off.result, b=11.0)

# ── Face Region Detection ─────────────────────────────────────────────
# Face area: Y=[10, 30], Z=[5, 14] (front of head)
face_y_gate_raw = float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=ABSOLUTE, a=ny.result))
face_y_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.5, b=face_y_gate_raw.result), b=0.0)
face_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.5, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
face_mask = float_math(operation=MULTIPLY, a=face_y_gate.result, b=face_z_gate.result)

# ── Eye Sockets ───────────────────────────────────────────────────────
# Eye sockets: recessed areas at Y~20, Z~10-12
eye_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.18)
eye_y_dist = float_math(operation=ABSOLUTE, a=eye_y_center.result)
eye_y_gate_raw = float_math(operation=SUBTRACT, a=0.15, b=eye_y_dist.result)
eye_y_gate = float_math(operation=MAXIMUM, a=eye_y_gate_raw.result, b=0.0)
eye_x_center = float_math(operation=SUBTRACT, a=nx.result, b=0.0)
eye_x_gate_raw = float_math(operation=SUBTRACT, a=0.12, b=float_math(operation=ABSOLUTE, a=eye_x_center.result))
eye_x_gate = float_math(operation=MAXIMUM, a=eye_x_gate_raw.result, b=0.0)
eye_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.8, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
eye_mask = float_math(operation=MULTIPLY, a=eye_y_gate.result, b=eye_x_gate.result)
eye_mask_full = float_math(operation=MULTIPLY, a=eye_mask.result, b=eye_z_gate.result)
eye_recess = float_math(operation=MULTIPLY, a=eye_mask_full.result, b=-0.8)

# ── Brow Ridge ────────────────────────────────────────────────────────
# Brow ridge: slight bulge above eyes at Y~22-24, Z~10-12
brow_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.25)
brow_y_gate_raw = float_math(operation=SUBTRACT, a=0.12, b=float_math(operation=ABSOLUTE, a=brow_y_center.result))
brow_y_gate = float_math(operation=MAXIMUM, a=brow_y_gate_raw.result, b=0.0)
brow_x_gate_raw = float_math(operation=SUBTRACT, a=0.15, b=float_math(operation=ABSOLUTE, a=nx.result))
brow_x_gate = float_math(operation=MAXIMUM, a=brow_x_gate_raw.result, b=0.0)
brow_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.75, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
brow_mask = float_math(operation=MULTIPLY, a=brow_y_gate.result, b=brow_x_gate.result)
brow_mask_full = float_math(operation=MULTIPLY, a=brow_mask.result, b=brow_z_gate.result)
brow_bulge = float_math(operation=MULTIPLY, a=brow_mask_full.result, b=0.3)

# ── Nose ──────────────────────────────────────────────────────────────
# Nose protrusion: centered at Y~18, Z~12-14
nose_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.05)
nose_y_gate_raw = float_math(operation=SUBTRACT, a=0.2, b=float_math(operation=ABSOLUTE, a=nose_y_center.result))
nose_y_gate = float_math(operation=MAXIMUM, a=nose_y_gate_raw.result, b=0.0)
nose_x_gate_raw = float_math(operation=SUBTRACT, a=0.1, b=float_math(operation=ABSOLUTE, a=nx.result))
nose_x_gate = float_math(operation=MAXIMUM, a=nose_x_gate_raw.result, b=0.0)
nose_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.7, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
nose_mask = float_math(operation=MULTIPLY, a=nose_y_gate.result, b=nose_x_gate.result)
nose_mask_full = float_math(operation=MULTIPLY, a=nose_mask.result, b=nose_z_gate.result)
nose_protrusion = float_math(operation=MULTIPLY, a=nose_mask_full.result, b=1.2)

# ── Mouth/Lips ────────────────────────────────────────────────────────
# Mouth area: Y~14-16, Z~10-12
mouth_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.1)
mouth_y_gate_raw = float_math(operation=SUBTRACT, a=0.12, b=float_math(operation=ABSOLUTE, a=mouth_y_center.result))
mouth_y_gate = float_math(operation=MAXIMUM, a=mouth_y_gate_raw.result, b=0.0)
mouth_x_gate_raw = float_math(operation=SUBTRACT, a=0.15, b=float_math(operation=ABSOLUTE, a=nx.result))
mouth_x_gate = float_math(operation=MAXIMUM, a=mouth_x_gate_raw.result, b=0.0)
mouth_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.75, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
mouth_mask = float_math(operation=MULTIPLY, a=mouth_y_gate.result, b=mouth_x_gate.result)
mouth_mask_full = float_math(operation=MULTIPLY, a=mouth_mask.result, b=mouth_z_gate.result)
mouth_lips = float_math(operation=MULTIPLY, a=mouth_mask_full.result, b=0.4)

# ── Chin ──────────────────────────────────────────────────────────────
# Chin: protrusion at bottom of face Y~10-14, Z~12-14
chin_y_center = float_math(operation=SUBTRACT, a=ny.result, b=-0.2)
chin_y_gate_raw = float_math(operation=SUBTRACT, a=0.15, b=float_math(operation=ABSOLUTE, a=chin_y_center.result))
chin_y_gate = float_math(operation=MAXIMUM, a=chin_y_gate_raw.result, b=0.0)
chin_x_gate_raw = float_math(operation=SUBTRACT, a=0.18, b=float_math(operation=ABSOLUTE, a=nx.result))
chin_x_gate = float_math(operation=MAXIMUM, a=chin_x_gate_raw.result, b=0.0)
chin_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.65, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
chin_mask = float_math(operation=MULTIPLY, a=chin_y_gate.result, b=chin_x_gate.result)
chin_mask_full = float_math(operation=MULTIPLY, a=chin_mask.result, b=chin_z_gate.result)
chin_protrusion = float_math(operation=MULTIPLY, a=chin_mask_full.result, b=0.6)

# ── Forehead ──────────────────────────────────────────────────────────
# Forehead: smooth area at top of face Y~24-30
forehead_y_center = float_math(operation=SUBTRACT, a=ny.result, b=0.35)
forehead_y_gate_raw = float_math(operation=SUBTRACT, a=0.2, b=float_math(operation=ABSOLUTE, a=forehead_y_center.result))
forehead_y_gate = float_math(operation=MAXIMUM, a=forehead_y_gate_raw.result, b=0.0)
forehead_x_gate_raw = float_math(operation=SUBTRACT, a=0.3, b=float_math(operation=ABSOLUTE, a=nx.result))
forehead_x_gate = float_math(operation=MAXIMUM, a=forehead_x_gate_raw.result, b=0.0)
forehead_z_gate = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=0.7, b=float_math(operation=ABSOLUTE, a=nz.result)), b=0.0)
forehead_mask = float_math(operation=MULTIPLY, a=forehead_y_gate.result, b=forehead_x_gate.result)
forehead_mask_full = float_math(operation=MULTIPLY, a=forehead_mask.result, b=forehead_z_gate.result)
forehead_smooth = float_math(operation=MULTIPLY, a=forehead_mask_full.result, b=0.2)

# ── Combine All Displacement ──────────────────────────────────────────
# Combine as Y offset + normal offset for features
f1 = float_math(operation=ADD, a=eye_recess.result, b=brow_bulge.result)
f2 = float_math(operation=ADD, a=f1.result, b=nose_protrusion.result)
f3 = float_math(operation=ADD, a=f2.result, b=mouth_lips.result)
f4 = float_math(operation=ADD, a=f3.result, b=chin_protrusion.result)
feature_disp = float_math(operation=ADD, a=f4.result, b=forehead_smooth.result)

# Apply displacement
nrm_xyz = separate_xyz(vector=nrm.vector)
disp_x = float_math(operation=MULTIPLY, a=nrm_xyz.x, b=feature_disp.result)
disp_y = float_math(operation=MULTIPLY, a=nrm_xyz.y, b=feature_disp.result)
disp_z = float_math(operation=MULTIPLY, a=nrm_xyz.z, b=feature_disp.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
head_shaped = set_position(geometry=head_assembly.geometry, offset=displacement.vector)

# ── Boolean Eye Sockets ───────────────────────────────────────────────
# Create eye socket cavities using boolean difference
eye_ball = uv_sphere(radius=0.5, segments=24, rings=16)
left_eye_socket = transform_geometry(geometry=eye_ball.mesh, translation=<-3.5, 20.0, 11.5>, scale=<1.2, 0.9, 0.8>)
right_eye_socket = transform_geometry(geometry=eye_ball.mesh, translation=<3.5, 20.0, 11.5>, scale=<1.2, 0.9, 0.8>)
head_minus_left_eye = mesh_boolean(mesh_a=head_shaped.geometry, mesh_b=left_eye_socket.geometry, operation=DIFFERENCE)
head_with_eyes = mesh_boolean(mesh_a=head_minus_left_eye.geometry, mesh_b=right_eye_socket.geometry, operation=DIFFERENCE)

# ── Tag Regions ───────────────────────────────────────────────────────
head_tagged = tag_geometry(geometry=head_with_eyes.geometry, tags="head,cranium,face,neck,shoulders,ears")
