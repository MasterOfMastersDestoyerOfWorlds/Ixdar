# Woman Sculpt — Stylized female figure approximation
# Reference: /Users/acw28/Blends/exports/woman_sculpt.obj
# Original: 843,828 verts, 1,687,628 faces, 141.6MB
# Dimensions: X=3.18, Y=3.37, Z=0.60, centroid=(0, 1.68, 0.05)
# Strategy: Composite primitives (spheres, cylinders) for major forms,
#           displacement for smoothing and proportion refinement
# Tags: head,ears,torso,arms,legs,feet

# ── Parameters ────────────────────────────────────────────────────────
subdivisions = input_int(name="subdivisions", default=8, min=4, max=12)
smoothness = input_float(name="smoothness", default=0.15, min=0.05, max=0.5)

# ── Head ──────────────────────────────────────────────────────────────
# Roughly spherical, slightly elongated vertically
head_sphere = uv_sphere(radius=0.35, segments=64, rings=48)
head_transformed = transform_geometry(geometry=head_sphere.mesh, translation=<0.0, 3.0, 0.0>, scale=<0.3, 0.385, 0.45>)

# ── Ears (pointed, elf-like) — using cylinders with tapered scale ──────
# Left ear
ear_left_cyl = cylinder(radius=0.12, height=0.25, segments=16)
ear_left = transform_geometry(geometry=ear_left_cyl.mesh, translation=<-0.35, 3.15, 0.0>, scale=<0.1, 0.25, 0.18>, rotation=<0.0, 0.0, -0.3>)

# Right ear
ear_right_cyl = cylinder(radius=0.12, height=0.25, segments=16)
ear_right = transform_geometry(geometry=ear_right_cyl.mesh, translation=<0.35, 3.15, 0.0>, scale=<0.1, 0.25, 0.18>, rotation=<0.0, 0.0, 0.3>)

# ── Torso (slender, hourglass shape) ──────────────────────────────────
# Elongated capsule/sphere hybrid for torso mass
torso_base = uv_sphere(radius=0.5, segments=48, rings=36)
torso = transform_geometry(geometry=torso_base.mesh, translation=<0.0, 2.1, 0.0>, scale=<0.38, 1.2, 0.5>)

# ── Shoulders / Upper Torso ───────────────────────────────────────────
# Slightly wider at shoulders than waist
shoulders = transform_geometry(geometry=uv_sphere(radius=0.5, segments=32, rings=24).mesh, translation=<0.0, 2.7, 0.0>, scale=<0.45, 0.4, 0.55>)

# ── Hips ──────────────────────────────────────────────────────────────
# Wider than shoulders, rounded
hips = transform_geometry(geometry=uv_sphere(radius=0.5, segments=32, rings=24).mesh, translation=<0.0, 1.3, 0.0>, scale=<0.42, 0.35, 0.55>)

# ── Arms (extended horizontally) ──────────────────────────────────────
# Cylinders extending from shoulders
# Left arm upper segment
left_arm_upper_cyl = cylinder(radius=0.09, height=0.65, segments=16)
left_arm_upper = transform_geometry(geometry=left_arm_upper_cyl.mesh, translation=<-0.65, 2.7, 0.05>, rotation=<0.0, 0.0, 0.15>, scale=<0.09, 0.65, 0.12>)

# Left arm lower segment
left_arm_lower_cyl = cylinder(radius=0.07, height=0.75, segments=16)
left_arm_lower = transform_geometry(geometry=left_arm_lower_cyl.mesh, translation=<-1.25, 2.7, 0.05>, rotation=<0.0, 0.0, 0.05>, scale=<0.07, 0.75, 0.1>)

# Right arm upper segment
right_arm_upper_cyl = cylinder(radius=0.09, height=0.65, segments=16)
right_arm_upper = transform_geometry(geometry=right_arm_upper_cyl.mesh, translation=<0.65, 2.7, 0.05>, rotation=<0.0, 0.0, -0.15>, scale=<0.09, 0.65, 0.12>)

# Right arm lower segment
right_arm_lower_cyl = cylinder(radius=0.07, height=0.75, segments=16)
right_arm_lower = transform_geometry(geometry=right_arm_lower_cyl.mesh, translation=<1.25, 2.7, 0.05>, rotation=<0.0, 0.0, -0.05>, scale=<0.07, 0.75, 0.1>)

# ── Hands (small spheres at arm ends) ─────────────────────────────────
left_hand = transform_geometry(geometry=uv_sphere(radius=0.06, segments=16, rings=12).mesh, translation=<-1.6, 2.7, 0.05>)
right_hand = transform_geometry(geometry=uv_sphere(radius=0.06, segments=16, rings=12).mesh, translation=<1.6, 2.7, 0.05>)

# ── Legs (elongated cylinders) ────────────────────────────────────────
# Two legs, each with thigh and calf segments
# Left thigh
left_thigh_cyl = cylinder(radius=0.12, height=0.85, segments=16)
left_thigh = transform_geometry(geometry=left_thigh_cyl.mesh, translation=<-0.075, 0.875, 0.05>, rotation=<0.1, 0.0, 0.0>, scale=<0.12, 0.85, 0.18>)

# Left calf
left_calf_cyl = cylinder(radius=0.09, height=0.75, segments=16)
left_calf = transform_geometry(geometry=left_calf_cyl.mesh, translation=<-0.075, 0.25, 0.05>, rotation=<-0.05, 0.0, 0.0>, scale=<0.09, 0.75, 0.15>)

# Right thigh
right_thigh_cyl = cylinder(radius=0.12, height=0.85, segments=16)
right_thigh = transform_geometry(geometry=right_thigh_cyl.mesh, translation=<0.075, 0.875, 0.05>, rotation=<0.1, 0.0, 0.0>, scale=<0.12, 0.85, 0.18>)

# Right calf
right_calf_cyl = cylinder(radius=0.09, height=0.75, segments=16)
right_calf = transform_geometry(geometry=right_calf_cyl.mesh, translation=<0.075, 0.25, 0.05>, rotation=<-0.05, 0.0, 0.0>, scale=<0.09, 0.75, 0.15>)

# ── Feet (small flattened spheres) ────────────────────────────────────
left_foot = transform_geometry(geometry=uv_sphere(radius=0.06, segments=16, rings=12).mesh, translation=<-0.075, -0.2, 0.05>, scale=<0.06, 0.08, 0.1>)
right_foot = transform_geometry(geometry=uv_sphere(radius=0.06, segments=16, rings=12).mesh, translation=<0.075, -0.2, 0.05>, scale=<0.06, 0.08, 0.1>)

# ── Neck (small cylinder connecting head to torso) ────────────────────
neck = transform_geometry(geometry=cylinder(radius=0.08, height=0.2, segments=12).mesh, translation=<0.0, 2.9, 0.0>)

# ── Assemble torso ────────────────────────────────────────────────────
torso_j1 = join_geometry(a=torso.geometry, b=shoulders.geometry)
torso_j2 = join_geometry(a=torso_j1.geometry, b=hips.geometry)
torso_j3 = join_geometry(a=torso_j2.geometry, b=neck.geometry)

# ── Assemble left arm ─────────────────────────────────────────────────
left_arm_j1 = join_geometry(a=left_arm_upper.geometry, b=left_arm_lower.geometry)
left_arm_j2 = join_geometry(a=left_arm_j1.geometry, b=left_hand.geometry)

# ── Assemble right arm ────────────────────────────────────────────────
right_arm_j1 = join_geometry(a=right_arm_upper.geometry, b=right_arm_lower.geometry)
right_arm_j2 = join_geometry(a=right_arm_j1.geometry, b=right_hand.geometry)

# ── Assemble left leg ─────────────────────────────────────────────────
left_leg_j1 = join_geometry(a=left_thigh.geometry, b=left_calf.geometry)
left_leg_j2 = join_geometry(a=left_leg_j1.geometry, b=left_foot.geometry)

# ── Assemble right leg ────────────────────────────────────────────────
right_leg_j1 = join_geometry(a=right_thigh.geometry, b=right_calf.geometry)
right_leg_j2 = join_geometry(a=right_leg_j1.geometry, b=right_foot.geometry)

# ── Final assembly ────────────────────────────────────────────────────
body_j1 = join_geometry(a=torso_j3.geometry, b=left_arm_j2.geometry)
body_j2 = join_geometry(a=body_j1.geometry, b=right_arm_j2.geometry)
body_j3 = join_geometry(a=body_j2.geometry, b=left_leg_j2.geometry)
body_j4 = join_geometry(a=body_j3.geometry, b=right_leg_j2.geometry)

# ── Add head and ears ─────────────────────────────────────────────────
full_body_j1 = join_geometry(a=body_j4.geometry, b=head_transformed.geometry)
full_body_j2 = join_geometry(a=full_body_j1.geometry, b=ear_left.geometry)
woman_sculpt = join_geometry(a=full_body_j2.geometry, b=ear_right.geometry)

# ── Tag regions for per-region quality metrics ────────────────────────
woman_tagged = tag_geometry(geometry=woman_sculpt.geometry, tags="woman_sculpt,head,ears,torso,shoulders,hips,arms,legs,feet,neck")
