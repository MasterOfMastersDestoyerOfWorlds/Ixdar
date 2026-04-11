# Test: single finger using DualRadialSegment + Hermite chaining + cap
# 3 phalanges (proximal, middle, distal) with G1 continuity at joints

segs = input_int(name="segments", default=12, min=6, max=24)
rings = input_int(name="rings", default=8, min=4, max=16)

# ── PROXIMAL PHALANX ──
# Elliptical cross-section: wider side-to-side (rx) than front-to-back (ry)
# Zero tangent at start (flat entry), slight negative tangent at end (tapering)
seg_prox = dual_radial_segment(
    start_rx=0.5, start_ry=0.6, start_tx=0.0, start_ty=0.0,
    end_rx=0.45, end_ry=0.55, end_tx=-0.1, end_ty=-0.1,
    length=1.5, rings=rings.result, segments=segs.result)

# ── MIDDLE PHALANX ──
# Consumes proximal's end conditions for G1 continuity
seg_mid = dual_radial_segment(
    geometry=seg_prox.geometry,
    start_rx=seg_prox.end_rx, start_ry=seg_prox.end_ry,
    start_tx=seg_prox.end_tx, start_ty=seg_prox.end_ty,
    end_rx=0.35, end_ry=0.42, end_tx=-0.15, end_ty=-0.15,
    length=1.0, rings=rings.result, segments=segs.result)

# ── DISTAL PHALANX ──
# Tapers to narrow tip
seg_dist = dual_radial_segment(
    geometry=seg_mid.geometry,
    start_rx=seg_mid.end_rx, start_ry=seg_mid.end_ry,
    start_tx=seg_mid.end_tx, start_ty=seg_mid.end_ty,
    end_rx=0.15, end_ry=0.18, end_tx=-0.2, end_ty=-0.2,
    length=0.7, rings=rings.result, segments=segs.result)

# ── CAP ──
tip = segment_cap(geometry=seg_dist.geometry, segments=segs.result)

# Output
out = transform_geometry(geometry=tip.geometry, scale=<1.0, 1.0, 1.0>)
