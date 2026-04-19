# Test: curve_to_mesh with radius_closure
# Verifies that the radius varies along the path via float_curve closure.
# Expected: a tube that starts thin, bulges in the middle, and tapers at the end.

# Straight path along Y axis
path = curve_bezier(resolution=40, start=<0.0, -2.0, 0.0>, handle_start=<0.0, -1.0, 0.0>, handle_end=<0.0, 1.0, 0.0>, end=<0.0, 2.0, 0.0>, mode=CUBIC)
path_r = resample_curve(curve=path.geometry, length=0.08)

# Radius varies: thin(0.2) → bulge(0.8) → thin(0.2)
radius_fc = float_curve(points="0,0.2, 0.5,0.8, 1.0,0.2")

# Sweep with varying radius
tube = curve_to_mesh(curve=path_r.geometry, radius=1.0, radius_closure=radius_fc.closure, resolution=16, fill_caps=true)
