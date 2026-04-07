# Test circle_curve primitive
# Validates the basic functionality of circle_curve

# Test 1: 4-point circle (square-ish)
circle4 = circle_curve(radius=1.0, resolution=4, center=<0.0, 0.0, 0.0>)

# Test 2: Default resolution
circle_default = circle_curve(radius=0.5, center=<0.0, 0.0, 0.0>)

# Test 3: Offset center
circle_offset = circle_curve(radius=0.25, resolution=16, center=<1.0, 2.0, 3.0>)

# Test 4: Use with birail_loft (inner and outer rails)
inner_rail = circle_curve(radius=0.5, resolution=32, center=<0.0, 0.0, 0.0>)
outer_rail = circle_curve(radius=0.7, resolution=32, center=<0.0, 0.0, 0.0>)
profile = curve_bezier(resolution=8, start=<0.0, -0.1, 0.0>, handle_start=<0.05, -0.08, 0.0>, handle_end=<0.05, 0.08, 0.0>, end=<0.0, 0.1, 0.0>, mode=LINEAR)
loft = birail_loft(rail_a=inner_rail.curve, rail_b=outer_rail.curve, u_segments=24, v_segments=16)
