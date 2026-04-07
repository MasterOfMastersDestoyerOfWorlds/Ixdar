# Test circle_curve with curve_sweep
# Validates circle_curve can be used as rail for curve_sweep

# Create a circular rail
circle = circle_curve(radius=1.0, resolution=32, center=<0.0, 0.0, 0.0>)

# Create a simple circular profile as a mesh
profile_circle = circle_curve(radius=0.1, resolution=8, center=<0.0, 0.0, 0.0>)
profile_mesh = curve_to_mesh(curve=profile_circle.curve, radius=0.05, resolution=8, fill_caps=false)

# Sweep the profile along the circle rail
tube = curve_sweep(curve=circle.curve, profile=profile_mesh.geometry, caps=true)

# Output the result
output = curve_to_mesh(curve=circle.curve, radius=0.15, resolution=32, fill_caps=true)
