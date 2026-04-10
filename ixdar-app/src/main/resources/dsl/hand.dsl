# Hand v2 — All-quad cylinders + Catmull-Clark subdivision
# Each body part is a quad_cylinder that subdivides into a smooth capsule.
# The overlapping capsule approach gives a smooth organic look without
# needing topology merging. CC subdivision rounds each piece nicely.

# ══════════════════════════════════════════════════════════════════════
# FOREARM
# ══════════════════════════════════════════════════════════════════════

forearm_cyl = quad_cylinder(radius=0.55, height=1.6, segments=8, rings=4, cap_rings=2)
forearm = transform_geometry(geometry=forearm_cyl.geometry, scale=<1.2, 1.0, 1.5>, translation=<0.0, -2.4, 0.0>)

# ══════════════════════════════════════════════════════════════════════
# WRIST + PALM
# ══════════════════════════════════════════════════════════════════════

wrist_cyl = quad_cylinder(radius=0.55, height=1.0, segments=8, rings=2, cap_rings=2)
wrist = transform_geometry(geometry=wrist_cyl.geometry, scale=<1.3, 1.0, 1.7>, translation=<0.0, -1.1, 0.0>)

palm_cyl = quad_cylinder(radius=0.55, height=1.2, segments=8, rings=3, cap_rings=2)
palm = transform_geometry(geometry=palm_cyl.geometry, scale=<1.5, 1.0, 2.0>, translation=<0.0, 0.2, 0.0>)

# ══════════════════════════════════════════════════════════════════════
# THUMB — 3 segments, angled
# ══════════════════════════════════════════════════════════════════════

thumb_meta_cyl = quad_cylinder(radius=0.24, height=0.8, segments=8, rings=2, cap_rings=1)
thumb_meta = transform_geometry(geometry=thumb_meta_cyl.geometry, rotation=<0.0, 0.0, 0.5>, translation=<-0.70, 0.2, 0.0>)

thumb_prox_cyl = quad_cylinder(radius=0.20, height=0.65, segments=8, rings=2, cap_rings=1)
thumb_prox = transform_geometry(geometry=thumb_prox_cyl.geometry, rotation=<0.0, 0.0, 0.4>, translation=<-0.55, 1.0, 0.05>)

thumb_dist_cyl = quad_cylinder(radius=0.17, height=0.55, segments=8, rings=1, cap_rings=1)
thumb_dist = transform_geometry(geometry=thumb_dist_cyl.geometry, rotation=<0.0, 0.0, 0.3>, translation=<-0.40, 1.6, 0.10>)

# ══════════════════════════════════════════════════════════════════════
# FINGERS — each has 3 phalanx segments
# ══════════════════════════════════════════════════════════════════════

# Index (Z = -0.75)
idx_prox_cyl = quad_cylinder(radius=0.16, height=0.65, segments=8, rings=2, cap_rings=1)
idx_prox = transform_geometry(geometry=idx_prox_cyl.geometry, translation=<-0.25, 1.35, -0.75>)
idx_mid_cyl = quad_cylinder(radius=0.14, height=0.50, segments=8, rings=2, cap_rings=1)
idx_mid = transform_geometry(geometry=idx_mid_cyl.geometry, translation=<-0.25, 1.90, -0.75>)
idx_dist_cyl = quad_cylinder(radius=0.12, height=0.40, segments=8, rings=1, cap_rings=1)
idx_dist = transform_geometry(geometry=idx_dist_cyl.geometry, translation=<-0.25, 2.30, -0.75>)

# Middle (Z = -0.25, longest)
mid_prox_cyl = quad_cylinder(radius=0.17, height=0.75, segments=8, rings=2, cap_rings=1)
mid_prox = transform_geometry(geometry=mid_prox_cyl.geometry, translation=<-0.15, 1.40, -0.25>)
mid_mid_cyl = quad_cylinder(radius=0.15, height=0.60, segments=8, rings=2, cap_rings=1)
mid_mid = transform_geometry(geometry=mid_mid_cyl.geometry, translation=<-0.15, 2.05, -0.25>)
mid_dist_cyl = quad_cylinder(radius=0.13, height=0.45, segments=8, rings=1, cap_rings=1)
mid_dist = transform_geometry(geometry=mid_dist_cyl.geometry, translation=<-0.15, 2.55, -0.25>)

# Ring (Z = 0.30)
ring_prox_cyl = quad_cylinder(radius=0.16, height=0.65, segments=8, rings=2, cap_rings=1)
ring_prox = transform_geometry(geometry=ring_prox_cyl.geometry, translation=<-0.15, 1.35, 0.30>)
ring_mid_cyl = quad_cylinder(radius=0.14, height=0.50, segments=8, rings=2, cap_rings=1)
ring_mid = transform_geometry(geometry=ring_mid_cyl.geometry, translation=<-0.15, 1.90, 0.30>)
ring_dist_cyl = quad_cylinder(radius=0.12, height=0.40, segments=8, rings=1, cap_rings=1)
ring_dist = transform_geometry(geometry=ring_dist_cyl.geometry, translation=<-0.15, 2.30, 0.30>)

# Pinky (Z = 0.80)
pink_prox_cyl = quad_cylinder(radius=0.13, height=0.50, segments=8, rings=2, cap_rings=1)
pink_prox = transform_geometry(geometry=pink_prox_cyl.geometry, translation=<-0.10, 1.25, 0.80>)
pink_mid_cyl = quad_cylinder(radius=0.11, height=0.40, segments=8, rings=2, cap_rings=1)
pink_mid = transform_geometry(geometry=pink_mid_cyl.geometry, translation=<-0.10, 1.60, 0.80>)
pink_dist_cyl = quad_cylinder(radius=0.09, height=0.30, segments=8, rings=1, cap_rings=1)
pink_dist = transform_geometry(geometry=pink_dist_cyl.geometry, translation=<-0.10, 1.90, 0.80>)

# ══════════════════════════════════════════════════════════════════════
# ASSEMBLY — join all, then subdivide
# ══════════════════════════════════════════════════════════════════════

j1 = join_geometry(a=forearm.geometry, b=wrist.geometry)
j2 = join_geometry(a=j1.geometry, b=palm.geometry)
# Thumb
j3 = join_geometry(a=j2.geometry, b=thumb_meta.geometry)
j4 = join_geometry(a=j3.geometry, b=thumb_prox.geometry)
j5 = join_geometry(a=j4.geometry, b=thumb_dist.geometry)
# Index
j6 = join_geometry(a=j5.geometry, b=idx_prox.geometry)
j7 = join_geometry(a=j6.geometry, b=idx_mid.geometry)
j8 = join_geometry(a=j7.geometry, b=idx_dist.geometry)
# Middle
j9 = join_geometry(a=j8.geometry, b=mid_prox.geometry)
j10 = join_geometry(a=j9.geometry, b=mid_mid.geometry)
j11 = join_geometry(a=j10.geometry, b=mid_dist.geometry)
# Ring
j12 = join_geometry(a=j11.geometry, b=ring_prox.geometry)
j13 = join_geometry(a=j12.geometry, b=ring_mid.geometry)
j14 = join_geometry(a=j13.geometry, b=ring_dist.geometry)
# Pinky
j15 = join_geometry(a=j14.geometry, b=pink_prox.geometry)
j16 = join_geometry(a=j15.geometry, b=pink_mid.geometry)
j17 = join_geometry(a=j16.geometry, b=pink_dist.geometry)

# Catmull-Clark — each piece becomes a smooth capsule
hand_tagged = subdivision_surface(geometry=j17.geometry, levels=2)
