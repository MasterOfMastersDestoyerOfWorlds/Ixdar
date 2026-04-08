# Hand — Capsule blockout with anatomical proportions
# Reference: /Users/acw28/Blends/Hand/Hand.obj (66K verts)
# Dimensions: X=2.44, Y=6.97, Z=4.21, centroid=(0.11, 0.18, -0.51)
# Orientation: Y-up (wrist-to-fingertips), X=width, Z=depth (palm-to-back)
# Strategy: capsule blockout — cylinders for bone segments, spheres at joints,
#           scaled ellipsoids for palm mass. Tags for per-region quality.

# ── Parameters ────────────────────────────────────────────────────────
segments = input_int(name="segments", default=16, min=8, max=32)

# ── Shared primitives (instanced per segment) ────────────────────────
bone_cyl = cylinder(radius=1.0, height=1.0, segments=16)
joint_ball = uv_sphere(radius=1.0, segments=16, rings=12)

# ══════════════════════════════════════════════════════════════════════
# FOREARM — two cylinders (radius, ulna) tapering to wrist
# ══════════════════════════════════════════════════════════════════════

# Forearm spans Y=[-3.3, -0.8], radius ~0.55 at elbow, ~0.4 at wrist
forearm = transform_geometry(geometry=bone_cyl.mesh, translation=<0.0, -2.05, -0.5>, scale=<0.55, 2.5, 0.9>)
wrist_joint = transform_geometry(geometry=joint_ball.mesh, translation=<0.0, -0.8, -0.5>, scale=<0.45, 0.3, 0.75>)

# ══════════════════════════════════════════════════════════════════════
# PALM — flattened ellipsoid for the metacarpal mass
# ══════════════════════════════════════════════════════════════════════

# Palm spans roughly Y=[-0.8, 1.5], X=[-1.0, 1.0], Z=[-1.2, 0.2]
palm_body = transform_geometry(geometry=joint_ball.mesh, translation=<0.1, 0.3, -0.5>, scale=<1.0, 1.2, 0.75>)

# Thenar eminence (thumb muscle pad, palm side)
thenar = transform_geometry(geometry=joint_ball.mesh, translation=<-0.65, -0.1, -0.3>, scale=<0.5, 0.7, 0.65>)

# Hypothenar eminence (pinky side pad)
hypothenar = transform_geometry(geometry=joint_ball.mesh, translation=<0.75, 0.0, -0.35>, scale=<0.35, 0.6, 0.55>)

# ══════════════════════════════════════════════════════════════════════
# THUMB — 2 phalanges (proximal + distal), offset and rotated
# ══════════════════════════════════════════════════════════════════════

# Metacarpal (hidden in thenar, angled out)
thumb_meta = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.8, 0.0, -0.1>, rotation=<0.0, 0.0, 0.5>, scale=<0.25, 0.7, 0.3>)
thumb_mcp = transform_geometry(geometry=joint_ball.mesh, translation=<-1.05, 0.55, 0.0>, scale=<0.28, 0.22, 0.32>)

# Proximal phalanx
thumb_prox = transform_geometry(geometry=bone_cyl.mesh, translation=<-1.15, 0.95, 0.1>, rotation=<0.0, 0.0, 0.3>, scale=<0.22, 0.55, 0.26>)
thumb_ip = transform_geometry(geometry=joint_ball.mesh, translation=<-1.2, 1.4, 0.15>, scale=<0.24, 0.19, 0.28>)

# Distal phalanx
thumb_dist = transform_geometry(geometry=bone_cyl.mesh, translation=<-1.2, 1.75, 0.2>, rotation=<0.0, 0.0, 0.15>, scale=<0.2, 0.45, 0.24>)
thumb_tip = transform_geometry(geometry=joint_ball.mesh, translation=<-1.2, 2.1, 0.22>, scale=<0.21, 0.17, 0.24>)

# ══════════════════════════════════════════════════════════════════════
# INDEX FINGER — 3 phalanges
# ══════════════════════════════════════════════════════════════════════

# Index — MCP at palm front, fingers curl toward +Z (palm curl)
idx_mcp = transform_geometry(geometry=joint_ball.mesh, translation=<-0.55, 1.4, -0.1>, scale=<0.22, 0.2, 0.26>)
idx_prox = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.55, 1.85, 0.1>, rotation=<0.25, 0.0, 0.0>, scale=<0.19, 0.55, 0.22>)
idx_pip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.55, 2.3, 0.35>, scale=<0.2, 0.17, 0.24>)
idx_mid = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.55, 2.6, 0.6>, rotation=<0.4, 0.0, 0.0>, scale=<0.17, 0.4, 0.2>)
idx_dip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.55, 2.85, 0.85>, scale=<0.18, 0.15, 0.22>)
idx_dist = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.55, 3.0, 1.05>, rotation=<0.5, 0.0, 0.0>, scale=<0.15, 0.32, 0.18>)
idx_tip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.55, 3.1, 1.25>, scale=<0.16, 0.14, 0.2>)

# ══════════════════════════════════════════════════════════════════════
# MIDDLE FINGER — 3 phalanges (longest finger)
# ══════════════════════════════════════════════════════════════════════

# Middle — longest finger, similar curl
mid_mcp = transform_geometry(geometry=joint_ball.mesh, translation=<-0.15, 1.55, -0.1>, scale=<0.22, 0.2, 0.26>)
mid_prox = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.15, 2.1, 0.15>, rotation=<0.25, 0.0, 0.0>, scale=<0.19, 0.65, 0.22>)
mid_pip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.15, 2.65, 0.45>, scale=<0.2, 0.17, 0.24>)
mid_mid = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.15, 2.95, 0.7>, rotation=<0.4, 0.0, 0.0>, scale=<0.17, 0.45, 0.2>)
mid_dip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.15, 3.2, 1.0>, scale=<0.18, 0.15, 0.22>)
mid_dist = transform_geometry(geometry=bone_cyl.mesh, translation=<-0.15, 3.35, 1.2>, rotation=<0.5, 0.0, 0.0>, scale=<0.15, 0.35, 0.18>)
mid_tip = transform_geometry(geometry=joint_ball.mesh, translation=<-0.15, 3.45, 1.45>, scale=<0.16, 0.14, 0.2>)

# ══════════════════════════════════════════════════════════════════════
# RING FINGER — 3 phalanges
# ══════════════════════════════════════════════════════════════════════

# Ring — slightly less curl than middle
ring_mcp = transform_geometry(geometry=joint_ball.mesh, translation=<0.25, 1.45, -0.1>, scale=<0.21, 0.19, 0.25>)
ring_prox = transform_geometry(geometry=bone_cyl.mesh, translation=<0.25, 1.9, 0.1>, rotation=<0.2, 0.0, 0.0>, scale=<0.18, 0.55, 0.21>)
ring_pip = transform_geometry(geometry=joint_ball.mesh, translation=<0.25, 2.38, 0.35>, scale=<0.19, 0.16, 0.23>)
ring_mid = transform_geometry(geometry=bone_cyl.mesh, translation=<0.25, 2.65, 0.55>, rotation=<0.35, 0.0, 0.0>, scale=<0.16, 0.4, 0.19>)
ring_dip = transform_geometry(geometry=joint_ball.mesh, translation=<0.25, 2.88, 0.78>, scale=<0.17, 0.14, 0.21>)
ring_dist = transform_geometry(geometry=bone_cyl.mesh, translation=<0.25, 3.0, 0.95>, rotation=<0.45, 0.0, 0.0>, scale=<0.14, 0.3, 0.17>)
ring_tip = transform_geometry(geometry=joint_ball.mesh, translation=<0.25, 3.08, 1.15>, scale=<0.15, 0.13, 0.19>)

# ══════════════════════════════════════════════════════════════════════
# PINKY FINGER — 3 phalanges (shortest)
# ══════════════════════════════════════════════════════════════════════

# Pinky — shortest, moderate curl
pinky_mcp = transform_geometry(geometry=joint_ball.mesh, translation=<0.65, 1.25, -0.08>, scale=<0.19, 0.17, 0.23>)
pinky_prox = transform_geometry(geometry=bone_cyl.mesh, translation=<0.65, 1.6, 0.08>, rotation=<0.2, 0.0, 0.0>, scale=<0.16, 0.42, 0.19>)
pinky_pip = transform_geometry(geometry=joint_ball.mesh, translation=<0.65, 1.95, 0.28>, scale=<0.17, 0.14, 0.21>)
pinky_mid = transform_geometry(geometry=bone_cyl.mesh, translation=<0.65, 2.12, 0.42>, rotation=<0.3, 0.0, 0.0>, scale=<0.14, 0.3, 0.17>)
pinky_dip = transform_geometry(geometry=joint_ball.mesh, translation=<0.65, 2.28, 0.58>, scale=<0.15, 0.12, 0.19>)
pinky_dist = transform_geometry(geometry=bone_cyl.mesh, translation=<0.65, 2.38, 0.72>, rotation=<0.4, 0.0, 0.0>, scale=<0.13, 0.25, 0.15>)
pinky_tip = transform_geometry(geometry=joint_ball.mesh, translation=<0.65, 2.45, 0.88>, scale=<0.14, 0.12, 0.17>)

# ══════════════════════════════════════════════════════════════════════
# TAG + ASSEMBLE — tag each sub-assembly, then join (tags merge via join)
# ══════════════════════════════════════════════════════════════════════

# --- Forearm sub-assembly ---
forearm_j = join_geometry(a=forearm.geometry, b=wrist_joint.geometry)
forearm_tagged = tag_geometry(geometry=forearm_j.geometry, tags="hand,forearm")

# --- Palm sub-assembly ---
palm_j1 = join_geometry(a=palm_body.geometry, b=thenar.geometry)
palm_j2 = join_geometry(a=palm_j1.geometry, b=hypothenar.geometry)
palm_tagged = tag_geometry(geometry=palm_j2.geometry, tags="hand,palm")

# --- Thumb sub-assembly (metacarpal + proximal + distal) ---
thumb_j1 = join_geometry(a=thumb_meta.geometry, b=thumb_mcp.geometry)
thumb_j1t = tag_geometry(geometry=thumb_j1.geometry, tags="hand,thumb,thumb_metacarpal")
thumb_j2 = join_geometry(a=thumb_prox.geometry, b=thumb_ip.geometry)
thumb_j2t = tag_geometry(geometry=thumb_j2.geometry, tags="hand,thumb,thumb_proximal")
thumb_j3 = join_geometry(a=thumb_dist.geometry, b=thumb_tip.geometry)
thumb_j3t = tag_geometry(geometry=thumb_j3.geometry, tags="hand,thumb,thumb_distal")
thumb_j4 = join_geometry(a=thumb_j1t.geometry, b=thumb_j2t.geometry)
thumb_tagged = join_geometry(a=thumb_j4.geometry, b=thumb_j3t.geometry)

# --- Index sub-assembly ---
idx_j1 = join_geometry(a=idx_mcp.geometry, b=idx_prox.geometry)
idx_j1t = tag_geometry(geometry=idx_j1.geometry, tags="hand,index,index_proximal")
idx_j2 = join_geometry(a=idx_pip.geometry, b=idx_mid.geometry)
idx_j2t = tag_geometry(geometry=idx_j2.geometry, tags="hand,index,index_middle")
idx_j3 = join_geometry(a=idx_dip.geometry, b=idx_dist.geometry)
idx_j4 = join_geometry(a=idx_j3.geometry, b=idx_tip.geometry)
idx_j4t = tag_geometry(geometry=idx_j4.geometry, tags="hand,index,index_distal")
idx_j5 = join_geometry(a=idx_j1t.geometry, b=idx_j2t.geometry)
idx_tagged = join_geometry(a=idx_j5.geometry, b=idx_j4t.geometry)

# --- Middle sub-assembly ---
mid_j1 = join_geometry(a=mid_mcp.geometry, b=mid_prox.geometry)
mid_j1t = tag_geometry(geometry=mid_j1.geometry, tags="hand,middle,middle_proximal")
mid_j2 = join_geometry(a=mid_pip.geometry, b=mid_mid.geometry)
mid_j2t = tag_geometry(geometry=mid_j2.geometry, tags="hand,middle,middle_middle")
mid_j3 = join_geometry(a=mid_dip.geometry, b=mid_dist.geometry)
mid_j4 = join_geometry(a=mid_j3.geometry, b=mid_tip.geometry)
mid_j4t = tag_geometry(geometry=mid_j4.geometry, tags="hand,middle,middle_distal")
mid_j5 = join_geometry(a=mid_j1t.geometry, b=mid_j2t.geometry)
mid_tagged = join_geometry(a=mid_j5.geometry, b=mid_j4t.geometry)

# --- Ring sub-assembly ---
ring_j1 = join_geometry(a=ring_mcp.geometry, b=ring_prox.geometry)
ring_j1t = tag_geometry(geometry=ring_j1.geometry, tags="hand,ring,ring_proximal")
ring_j2 = join_geometry(a=ring_pip.geometry, b=ring_mid.geometry)
ring_j2t = tag_geometry(geometry=ring_j2.geometry, tags="hand,ring,ring_middle")
ring_j3 = join_geometry(a=ring_dip.geometry, b=ring_dist.geometry)
ring_j4 = join_geometry(a=ring_j3.geometry, b=ring_tip.geometry)
ring_j4t = tag_geometry(geometry=ring_j4.geometry, tags="hand,ring,ring_distal")
ring_j5 = join_geometry(a=ring_j1t.geometry, b=ring_j2t.geometry)
ring_tagged = join_geometry(a=ring_j5.geometry, b=ring_j4t.geometry)

# --- Pinky sub-assembly ---
pinky_j1 = join_geometry(a=pinky_mcp.geometry, b=pinky_prox.geometry)
pinky_j1t = tag_geometry(geometry=pinky_j1.geometry, tags="hand,pinky,pinky_proximal")
pinky_j2 = join_geometry(a=pinky_pip.geometry, b=pinky_mid.geometry)
pinky_j2t = tag_geometry(geometry=pinky_j2.geometry, tags="hand,pinky,pinky_middle")
pinky_j3 = join_geometry(a=pinky_dip.geometry, b=pinky_dist.geometry)
pinky_j4 = join_geometry(a=pinky_j3.geometry, b=pinky_tip.geometry)
pinky_j4t = tag_geometry(geometry=pinky_j4.geometry, tags="hand,pinky,pinky_distal")
pinky_j5 = join_geometry(a=pinky_j1t.geometry, b=pinky_j2t.geometry)
pinky_tagged = join_geometry(a=pinky_j5.geometry, b=pinky_j4t.geometry)

# --- Final assembly ---
fa1 = join_geometry(a=forearm_tagged.geometry, b=palm_tagged.geometry)
fa2 = join_geometry(a=fa1.geometry, b=thumb_tagged.geometry)
fa3 = join_geometry(a=fa2.geometry, b=idx_tagged.geometry)
fa4 = join_geometry(a=fa3.geometry, b=mid_tagged.geometry)
fa5 = join_geometry(a=fa4.geometry, b=ring_tagged.geometry)
hand_tagged = join_geometry(a=fa5.geometry, b=pinky_tagged.geometry)
