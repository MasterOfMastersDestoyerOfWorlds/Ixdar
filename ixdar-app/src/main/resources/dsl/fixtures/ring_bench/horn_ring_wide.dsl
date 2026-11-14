# Ring fit, horn: a unit sphere with a cone for a horn on +y, the cone's base inside the sphere
# and its tip outside, so the two meet in a groove round the horn's foot (y 0.96, radius 0.29).
# A ring drawn wide round the horn, out on the sphere, should be fitted in to that
# groove.
ball = icosphere(radius=1.0, subdivisions=5)
# The cone is a tube of quads narrowed toward its top, so it has even rows of vertices all the
# way up; its tip is left a twentieth of its base wide.
tube = quad_cylinder(radius=0.4, height=1.2, segments=32, rings=16, cap_rings=2)
pos = input_position()
xyz = separate_xyz(vector=pos.vector)
up = float_math(operation=ADD, a=float_math(operation=DIVIDE, a=xyz.y, b=1.2).result, b=0.5)
narrow = float_math(operation=MULTIPLY, a=up.result, b=-0.95)
narrow_x = float_math(operation=MULTIPLY, a=xyz.x, b=narrow.result)
narrow_z = float_math(operation=MULTIPLY, a=xyz.z, b=narrow.result)
narrow_y = float_math(operation=MULTIPLY, a=xyz.y, b=0.0)
narrowing = combine_xyz(x=narrow_x.result, y=narrow_y.result, z=narrow_z.result)
spike = set_position(geometry=tube.geometry, offset=narrowing.vector)
spike_placed = transform_geometry(geometry=spike.geometry, translation=<0.0, 1.2, 0.0>)
horned = mesh_boolean(mesh_a=ball.mesh, mesh_b=spike_placed.geometry, operation=UNION)
surface = surface_metric(geometry=horned.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="0.714398,0.699740,0.000000; 0.341154,0.706340,-0.620239; -0.351204,0.702409,-0.619094; -0.704140,0.709761,-0.020642; -0.351204,0.702409,0.619094; 0.341154,0.706340,0.620239", normal="0.436934,-0.417011,-0.796988", label="ring_00")
