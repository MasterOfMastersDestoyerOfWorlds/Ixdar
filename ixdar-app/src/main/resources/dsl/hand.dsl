# Hand — Hybrid: ellipsoid body + curve-swept fingers
# Reference: /Users/acw28/Blends/Hand/Hand.obj (260K verts)
# Strategy: P95 ellipsoids for forearm/wrist/palm (proven 88.5%),
#   curve_to_mesh with radius_closure for individual fingers (no inter-finger gaps).

ball = uv_sphere(radius=1.0, segments=24, rings=16)

# Shared: circle profile rotated to XY plane for curve_to_mesh
profile_xz = circle_curve(radius=1.0, resolution=16)
profile_xy = transform_geometry(geometry=profile_xz.curve, rotation=<1.5708, 0.0, 0.0>)

# ══════════════════════════════════════════════════════════════════════
# FOREARM — Y=-3.2 to -1.6 (ellipsoids from reference cross-section data)
# ══════════════════════════════════════════════════════════════════════

fa_0 = transform_geometry(geometry=ball.mesh, translation=<-0.571, -3.2, -0.062>, scale=<0.445, 0.20, 0.826>)
fa_1 = transform_geometry(geometry=ball.mesh, translation=<-0.442, -3.0, 0.042>, scale=<0.598, 0.20, 0.879>)
fa_2 = transform_geometry(geometry=ball.mesh, translation=<-0.418, -2.8, -0.006>, scale=<0.654, 0.20, 0.894>)
fa_3 = transform_geometry(geometry=ball.mesh, translation=<-0.390, -2.6, -0.016>, scale=<0.671, 0.20, 0.894>)
fa_4 = transform_geometry(geometry=ball.mesh, translation=<-0.420, -2.4, 0.021>, scale=<0.672, 0.20, 0.887>)
fa_5 = transform_geometry(geometry=ball.mesh, translation=<-0.445, -2.2, 0.009>, scale=<0.661, 0.20, 0.880>)
fa_6 = transform_geometry(geometry=ball.mesh, translation=<-0.441, -2.0, -0.002>, scale=<0.654, 0.20, 0.872>)
fa_7 = transform_geometry(geometry=ball.mesh, translation=<-0.422, -1.8, -0.026>, scale=<0.645, 0.20, 0.859>)
fa_8 = transform_geometry(geometry=ball.mesh, translation=<-0.472, -1.6, -0.050>, scale=<0.625, 0.20, 0.850>)

fj1 = join_geometry(a=fa_0.geometry, b=fa_1.geometry)
fj2 = join_geometry(a=fj1.geometry, b=fa_2.geometry)
fj3 = join_geometry(a=fj2.geometry, b=fa_3.geometry)
fj4 = join_geometry(a=fj3.geometry, b=fa_4.geometry)
fj5 = join_geometry(a=fj4.geometry, b=fa_5.geometry)
fj6 = join_geometry(a=fj5.geometry, b=fa_6.geometry)
fj7 = join_geometry(a=fj6.geometry, b=fa_7.geometry)
fj8 = join_geometry(a=fj7.geometry, b=fa_8.geometry)
forearm_tagged = tag_geometry(geometry=fj8.geometry, tags="hand,forearm")

# ══════════════════════════════════════════════════════════════════════
# WRIST + PALM — Y=-1.4 to +1.0 (ellipsoids)
# ══════════════════════════════════════════════════════════════════════

wp_0 = transform_geometry(geometry=ball.mesh, translation=<-0.330, -1.4, -0.061>, scale=<0.632, 0.20, 0.941>)
wp_1 = transform_geometry(geometry=ball.mesh, translation=<-0.280, -1.2, -0.127>, scale=<0.681, 0.20, 1.112>)
wp_2 = transform_geometry(geometry=ball.mesh, translation=<-0.206, -1.0, -0.252>, scale=<0.750, 0.20, 1.266>)
wp_3 = transform_geometry(geometry=ball.mesh, translation=<-0.192, -0.8, -0.332>, scale=<0.792, 0.20, 1.416>)
wp_4 = transform_geometry(geometry=ball.mesh, translation=<-0.220, -0.6, -0.441>, scale=<0.813, 0.20, 1.521>)
wp_5 = transform_geometry(geometry=ball.mesh, translation=<-0.211, -0.4, -0.509>, scale=<0.850, 0.20, 1.608>)
wp_6 = transform_geometry(geometry=ball.mesh, translation=<-0.206, -0.2, -0.563>, scale=<0.915, 0.20, 1.667>)
wp_7 = transform_geometry(geometry=ball.mesh, translation=<-0.077, 0.0, -0.758>, scale=<0.995, 0.20, 1.748>)
wp_8 = transform_geometry(geometry=ball.mesh, translation=<-0.024, 0.2, -0.811>, scale=<1.076, 0.20, 1.787>)
wp_9 = transform_geometry(geometry=ball.mesh, translation=<-0.149, 0.4, -0.606>, scale=<1.148, 0.20, 1.845>)
wp_10 = transform_geometry(geometry=ball.mesh, translation=<-0.227, 0.6, -0.475>, scale=<1.213, 0.20, 1.892>)
wp_11 = transform_geometry(geometry=ball.mesh, translation=<-0.046, 0.8, -0.690>, scale=<1.220, 0.20, 1.912>)
wp_12 = transform_geometry(geometry=ball.mesh, translation=<-0.519, 1.0, 0.071>, scale=<1.123, 0.20, 1.883>)

# Palm deep -Z fills
palm_deep = transform_geometry(geometry=ball.mesh, translation=<-0.07, 0.7, -1.8>, scale=<0.55, 0.45, 0.55>)
palm_plus_x = transform_geometry(geometry=ball.mesh, translation=<0.70, 0.7, -0.50>, scale=<0.35, 0.55, 0.85>)
thenar = transform_geometry(geometry=ball.mesh, translation=<-0.85, 0.0, -0.40>, scale=<0.42, 0.75, 0.95>)
hypothenar = transform_geometry(geometry=ball.mesh, translation=<0.75, 0.3, -0.35>, scale=<0.30, 0.65, 0.80>)
knuckle_back = transform_geometry(geometry=ball.mesh, translation=<1.0, 0.75, -2.30>, scale=<0.22, 0.50, 0.28>)
wrist_px = transform_geometry(geometry=ball.mesh, translation=<0.35, -0.5, -1.20>, scale=<0.28, 0.55, 0.40>)

wj1 = join_geometry(a=wp_0.geometry, b=wp_1.geometry)
wj2 = join_geometry(a=wj1.geometry, b=wp_2.geometry)
wj3 = join_geometry(a=wj2.geometry, b=wp_3.geometry)
wj4 = join_geometry(a=wj3.geometry, b=wp_4.geometry)
wj5 = join_geometry(a=wj4.geometry, b=wp_5.geometry)
wj6 = join_geometry(a=wj5.geometry, b=wp_6.geometry)
wj7 = join_geometry(a=wj6.geometry, b=wp_7.geometry)
wj8 = join_geometry(a=wj7.geometry, b=wp_8.geometry)
wj9 = join_geometry(a=wj8.geometry, b=wp_9.geometry)
wj10 = join_geometry(a=wj9.geometry, b=wp_10.geometry)
wj11 = join_geometry(a=wj10.geometry, b=wp_11.geometry)
wj12 = join_geometry(a=wj11.geometry, b=wp_12.geometry)
wj13 = join_geometry(a=wj12.geometry, b=palm_deep.geometry)
wj14 = join_geometry(a=wj13.geometry, b=palm_plus_x.geometry)
wj15 = join_geometry(a=wj14.geometry, b=thenar.geometry)
wj16 = join_geometry(a=wj15.geometry, b=hypothenar.geometry)
wj17 = join_geometry(a=wj16.geometry, b=knuckle_back.geometry)
wj18 = join_geometry(a=wj17.geometry, b=wrist_px.geometry)
palm_tagged = tag_geometry(geometry=wj18.geometry, tags="hand,palm")

# ══════════════════════════════════════════════════════════════════════
# THUMB — curve sweep with radius_closure
# ══════════════════════════════════════════════════════════════════════

thumb_rail = curve_bezier(resolution=24, start=<-0.85, -0.3, -0.10>, handle_start=<-0.88, 0.3, 0.00>, handle_end=<-0.80, 1.2, 0.20>, end=<-0.63, 2.2, 0.07>, mode=CUBIC)
thumb_rail_r = resample_curve(curve=thumb_rail.curve, length=0.06)
thumb_radius = float_curve(points="0,0.30, 0.4,0.28, 0.7,0.24, 1.0,0.18")
thumb_mesh = curve_to_mesh(curve=thumb_rail_r.curve, radius=1.0, radius_closure=thumb_radius.closure, resolution=12, fill_caps=true)
thumb_tagged = tag_geometry(geometry=thumb_mesh.geometry, tags="hand,thumb")

# ══════════════════════════════════════════════════════════════════════
# FINGERS — individual curve sweeps with radius_closure
# From cluster analysis: 4 distinct finger paths at Y>=1.2
# ══════════════════════════════════════════════════════════════════════

# Finger A — Pinky (highest +Z): Y=1.2→2.5
fA_rail = curve_bezier(resolution=20, start=<-0.50, 1.2, 1.10>, handle_start=<-0.45, 1.6, 1.20>, handle_end=<-0.10, 2.0, 1.35>, end=<0.38, 2.5, 1.42>, mode=CUBIC)
fA_rail_r = resample_curve(curve=fA_rail.curve, length=0.06)
fA_radius = float_curve(points="0,0.30, 0.5,0.25, 1.0,0.18")
fA_mesh = curve_to_mesh(curve=fA_rail_r.curve, radius=1.0, radius_closure=fA_radius.closure, resolution=10, fill_caps=true)
fA_tagged = tag_geometry(geometry=fA_mesh.geometry, tags="hand,fingers,pinky")

# Finger B — Ring (Z≈0.5..0.6): Y=1.2→3.3
fB_rail = curve_bezier(resolution=24, start=<-0.50, 1.2, 0.55>, handle_start=<-0.45, 1.6, 0.56>, handle_end=<-0.20, 2.4, 0.60>, end=<0.25, 3.3, 0.55>, mode=CUBIC)
fB_rail_r = resample_curve(curve=fB_rail.curve, length=0.06)
fB_radius = float_curve(points="0,0.33, 0.3,0.30, 0.7,0.25, 1.0,0.18")
fB_mesh = curve_to_mesh(curve=fB_rail_r.curve, radius=1.0, radius_closure=fB_radius.closure, resolution=10, fill_caps=true)
fB_tagged = tag_geometry(geometry=fB_mesh.geometry, tags="hand,fingers,ring")

# Finger C — Middle (Z≈-0.3, longest): Y=1.2→3.6
fC_rail = curve_bezier(resolution=28, start=<-0.52, 1.2, -0.20>, handle_start=<-0.50, 1.8, -0.25>, handle_end=<-0.15, 2.8, -0.30>, end=<0.40, 3.6, -0.30>, mode=CUBIC)
fC_rail_r = resample_curve(curve=fC_rail.curve, length=0.06)
fC_radius = float_curve(points="0,0.35, 0.3,0.32, 0.6,0.28, 0.85,0.22, 1.0,0.17")
fC_mesh = curve_to_mesh(curve=fC_rail_r.curve, radius=1.0, radius_closure=fC_radius.closure, resolution=10, fill_caps=true)
fC_tagged = tag_geometry(geometry=fC_mesh.geometry, tags="hand,fingers,middle")

# Finger D — Index (most -Z, Z≈-1.0..-1.1): Y=1.2→3.2
fD_rail = curve_bezier(resolution=24, start=<-0.35, 1.2, -0.90>, handle_start=<-0.25, 1.7, -0.98>, handle_end=<0.10, 2.5, -1.08>, end=<0.55, 3.2, -1.10>, mode=CUBIC)
fD_rail_r = resample_curve(curve=fD_rail.curve, length=0.06)
fD_radius = float_curve(points="0,0.33, 0.3,0.30, 0.7,0.25, 1.0,0.18")
fD_mesh = curve_to_mesh(curve=fD_rail_r.curve, radius=1.0, radius_closure=fD_radius.closure, resolution=10, fill_caps=true)
fD_tagged = tag_geometry(geometry=fD_mesh.geometry, tags="hand,fingers,index")

# Ring/pinky tip extension (from error analysis)
ring_tip = transform_geometry(geometry=ball.mesh, translation=<0.75, 3.2, -1.10>, scale=<0.18, 0.35, 0.25>)
ring_tip_tagged = tag_geometry(geometry=ring_tip.geometry, tags="hand,fingers")

# ══════════════════════════════════════════════════════════════════════
# ASSEMBLY
# ══════════════════════════════════════════════════════════════════════

j1 = join_geometry(a=forearm_tagged.geometry, b=palm_tagged.geometry)
j2 = join_geometry(a=j1.geometry, b=thumb_tagged.geometry)
j3 = join_geometry(a=j2.geometry, b=fA_tagged.geometry)
j4 = join_geometry(a=j3.geometry, b=fB_tagged.geometry)
j5 = join_geometry(a=j4.geometry, b=fC_tagged.geometry)
j6 = join_geometry(a=j5.geometry, b=fD_tagged.geometry)
hand_tagged = join_geometry(a=j6.geometry, b=ring_tip_tagged.geometry)
