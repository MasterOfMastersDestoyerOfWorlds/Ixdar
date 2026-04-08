# Hand — Overlapping ellipsoid volumes matched to reference cross-sections
# Reference: /Users/acw28/Blends/Hand/Hand.obj (66K verts)
# Measured cross-section data used for precise placement:
#   Y=-2.5: center(-0.42, 0.0)  halfX=0.68 halfZ=0.90
#   Y=-1.5: center(-0.42,-0.06) halfX=0.62 halfZ=0.90
#   Y=-0.5: center(-0.22,-0.47) halfX=0.84 halfZ=1.59
#   Y= 0.0: center(-0.09,-0.73) halfX=1.01 halfZ=1.76
#   Y= 0.5: center(-0.20,-0.52) halfX=1.20 halfZ=1.88
#   Y= 1.0: center(-0.36,-0.18) halfX=1.20 halfZ=1.95
#   Y= 1.5: center(-0.47, 0.12) halfX=0.44 halfZ=1.36
#   Y= 2.0: center(-0.28, 0.13) halfX=0.57 halfZ=1.43
#   Y= 2.5: center(-0.12,-0.02) halfX=0.64 halfZ=1.46
#   Y= 3.0: center( 0.19,-0.31) halfX=0.63 halfZ=1.09
#   Y= 3.5: center( 0.34,-0.31) halfX=0.30 halfZ=0.20

# ── Shared primitives ────────────────────────────────────────────────
ball = uv_sphere(radius=1.0, segments=24, rings=16)
cyl = cylinder(radius=1.0, height=1.0, segments=24)

# ══════════════════════════════════════════════════════════════════════
# FOREARM — elongated cylinder Y=[-3.3, -0.8]
# Center at (-0.42, -2.05, 0.0), halfX=0.68, halfZ=0.90, height=2.5
# ══════════════════════════════════════════════════════════════════════
# Reference forearm: X=[-1.10,0.26], Z=[-0.87,0.92] → halfX=0.68, halfZ=0.90
# Use overlapping ellipsoids for smoother forearm shape
forearm_lo = transform_geometry(geometry=ball.mesh, translation=<-0.42, -2.8, 0.0>, scale=<0.62, 0.65, 0.85>)
forearm_mid = transform_geometry(geometry=ball.mesh, translation=<-0.42, -2.0, -0.02>, scale=<0.60, 0.65, 0.85>)
forearm_hi = transform_geometry(geometry=ball.mesh, translation=<-0.42, -1.3, -0.06>, scale=<0.58, 0.55, 0.85>)
forearm_bot = transform_geometry(geometry=ball.mesh, translation=<-0.42, -3.25, 0.0>, scale=<0.65, 0.25, 0.88>)

# ══════════════════════════════════════════════════════════════════════
# WRIST TRANSITION Y=[-1.0, -0.3] — widens from forearm to palm
# ══════════════════════════════════════════════════════════════════════
wrist = transform_geometry(geometry=ball.mesh, translation=<-0.30, -0.65, -0.15>, scale=<0.78, 0.55, 1.2>)

# ══════════════════════════════════════════════════════════════════════
# PALM — large flattened ellipsoid Y=[-0.5, 1.3]
# Widest part of hand, deep Z, center shifts to Z=-0.7
# ══════════════════════════════════════════════════════════════════════
# Main palm mass — tight fit to reference cross-section at Y=0
# ref: center(-0.09,-0.73) halfX=1.01 halfZ=1.76
palm_core = transform_geometry(geometry=ball.mesh, translation=<-0.09, 0.0, -0.73>, scale=<0.88, 0.75, 1.55>)
# Upper palm at Y=0.5: center(-0.20,-0.52) halfX=1.20 halfZ=1.88
palm_upper = transform_geometry(geometry=ball.mesh, translation=<-0.20, 0.5, -0.52>, scale=<1.00, 0.55, 1.65>)
# Palm-finger transition at Y=1.0: center(-0.36,-0.18) halfX=1.20 halfZ=1.95
palm_top = transform_geometry(geometry=ball.mesh, translation=<-0.36, 1.0, -0.18>, scale=<0.95, 0.45, 1.65>)

# Thenar eminence (thumb pad — bulges at -X, Y~0, Z~-0.5)
thenar = transform_geometry(geometry=ball.mesh, translation=<-0.85, 0.0, -0.40>, scale=<0.45, 0.8, 1.0>)

# Hypothenar (pinky pad — +X side)
hypothenar = transform_geometry(geometry=ball.mesh, translation=<0.75, 0.3, -0.35>, scale=<0.35, 0.7, 0.9>)

# Knuckle back — ref shows mass at X=0.8..1.3, Z=-2.6..-2.0, Y=0.5..1.0
knuckle_back = transform_geometry(geometry=ball.mesh, translation=<1.0, 0.75, -2.30>, scale=<0.22, 0.50, 0.28>)

# Wrist +X transition — undershoot at Y=-1.0..0.0, X=0.35, Z=-1.25
wrist_plus_x = transform_geometry(geometry=ball.mesh, translation=<0.35, -0.5, -1.20>, scale=<0.30, 0.6, 0.45>)

# Ring/pinky fingertip Z extension — Y=3.5, X=0.78, Z=-1.17
ring_tip_ext = transform_geometry(geometry=ball.mesh, translation=<0.75, 3.2, -1.10>, scale=<0.18, 0.35, 0.25>)

# ══════════════════════════════════════════════════════════════════════
# THUMB — traced path from reference data
# X=-0.85 to -0.63, Y=-0.5 to 2.25, Z=-0.2 to 0.2
# rX=0.22 to 0.30, rZ measured ~1.2 (includes palm overlap)
# Actual thumb radius ~0.25
# ══════════════════════════════════════════════════════════════════════
# Thumb metacarpal (hidden in thenar)
thumb_mc = transform_geometry(geometry=cyl.mesh, translation=<-0.85, -0.1, -0.10>, rotation=<0.0, 0.0, 0.4>, scale=<0.27, 0.8, 0.30>)
# MCP joint
thumb_mcp = transform_geometry(geometry=ball.mesh, translation=<-0.88, 0.5, 0.04>, scale=<0.30, 0.25, 0.32>)
# Proximal phalanx
thumb_pp = transform_geometry(geometry=cyl.mesh, translation=<-0.84, 1.0, 0.20>, rotation=<-0.1, 0.0, 0.2>, scale=<0.28, 0.65, 0.30>)
# IP joint
thumb_ip = transform_geometry(geometry=ball.mesh, translation=<-0.77, 1.5, 0.18>, scale=<0.22, 0.2, 0.25>)
# Distal phalanx
thumb_dp = transform_geometry(geometry=cyl.mesh, translation=<-0.70, 1.9, 0.12>, rotation=<0.0, 0.0, 0.15>, scale=<0.18, 0.45, 0.20>)
# Tip
thumb_tip = transform_geometry(geometry=ball.mesh, translation=<-0.63, 2.2, 0.07>, scale=<0.15, 0.14, 0.17>)

# ══════════════════════════════════════════════════════════════════════
# FINGER MASS — the fingers are one continuous mass in the reference
# They don't separate. Model as overlapping Y-elongated ellipsoids
# that taper from Y=1.5 (wide) to Y=3.5 (narrow).
# The mass shifts from X=-0.47 to X=+0.34 as it extends.
# ══════════════════════════════════════════════════════════════════════

# Lower finger mass (Y=1.2..2.5)
fingers_lo = transform_geometry(geometry=ball.mesh, translation=<-0.30, 1.8, 0.0>, scale=<0.65, 0.8, 1.40>)

# Mid finger mass (Y=2.0..3.0)
fingers_mid = transform_geometry(geometry=ball.mesh, translation=<-0.05, 2.5, -0.05>, scale=<0.70, 0.7, 1.45>)

# Upper finger mass (Y=2.5..3.5) — shifts toward +X
fingers_hi = transform_geometry(geometry=ball.mesh, translation=<0.15, 3.0, -0.30>, scale=<0.65, 0.6, 1.10>)

# Fingertips (Y=3.0..3.65) — narrow
fingers_tips = transform_geometry(geometry=ball.mesh, translation=<0.34, 3.3, -0.30>, scale=<0.32, 0.35, 0.25>)

# Additional mass to fill palm-finger junction at Z extremes
# The reference has Z span of ~2.9 at Y=1.5 and ~2.7 at Y=2.5
finger_front = transform_geometry(geometry=ball.mesh, translation=<-0.15, 2.0, 1.2>, scale=<0.55, 0.8, 0.4>)
finger_back = transform_geometry(geometry=ball.mesh, translation=<-0.15, 2.0, -1.2>, scale=<0.55, 0.8, 0.4>)

# ══════════════════════════════════════════════════════════════════════
# ASSEMBLY — tag then join
# ══════════════════════════════════════════════════════════════════════

# Forearm
fa_j1 = join_geometry(a=forearm_lo.geometry, b=forearm_mid.geometry)
fa_j2 = join_geometry(a=fa_j1.geometry, b=forearm_hi.geometry)
fa_j3 = join_geometry(a=fa_j2.geometry, b=forearm_bot.geometry)
forearm_tagged = tag_geometry(geometry=fa_j3.geometry, tags="hand,forearm")

# Wrist
wrist_tagged = tag_geometry(geometry=wrist.geometry, tags="hand,wrist")

# Palm
pa_j1 = join_geometry(a=palm_core.geometry, b=palm_upper.geometry)
pa_j2 = join_geometry(a=pa_j1.geometry, b=palm_top.geometry)
pa_j3 = join_geometry(a=pa_j2.geometry, b=thenar.geometry)
pa_j4 = join_geometry(a=pa_j3.geometry, b=hypothenar.geometry)
pa_j5 = join_geometry(a=pa_j4.geometry, b=knuckle_back.geometry)
pa_j6 = join_geometry(a=pa_j5.geometry, b=wrist_plus_x.geometry)
palm_tagged = tag_geometry(geometry=pa_j6.geometry, tags="hand,palm")

# Thumb
th_j1 = join_geometry(a=thumb_mc.geometry, b=thumb_mcp.geometry)
th_j2 = join_geometry(a=th_j1.geometry, b=thumb_pp.geometry)
th_j3 = join_geometry(a=th_j2.geometry, b=thumb_ip.geometry)
th_j4 = join_geometry(a=th_j3.geometry, b=thumb_dp.geometry)
th_j5 = join_geometry(a=th_j4.geometry, b=thumb_tip.geometry)
thumb_tagged = tag_geometry(geometry=th_j5.geometry, tags="hand,thumb")

# Fingers
fi_j1 = join_geometry(a=fingers_lo.geometry, b=fingers_mid.geometry)
fi_j2 = join_geometry(a=fi_j1.geometry, b=fingers_hi.geometry)
fi_j3 = join_geometry(a=fi_j2.geometry, b=fingers_tips.geometry)
fi_j4 = join_geometry(a=fi_j3.geometry, b=finger_front.geometry)
fi_j5 = join_geometry(a=fi_j4.geometry, b=finger_back.geometry)
fi_j6 = join_geometry(a=fi_j5.geometry, b=ring_tip_ext.geometry)
fingers_tagged = tag_geometry(geometry=fi_j6.geometry, tags="hand,fingers")

# Final join
j1 = join_geometry(a=forearm_tagged.geometry, b=wrist_tagged.geometry)
j2 = join_geometry(a=j1.geometry, b=palm_tagged.geometry)
j3 = join_geometry(a=j2.geometry, b=thumb_tagged.geometry)
hand_tagged = join_geometry(a=j3.geometry, b=fingers_tagged.geometry)
