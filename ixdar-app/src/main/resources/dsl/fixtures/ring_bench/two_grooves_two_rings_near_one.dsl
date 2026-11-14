# Ring fit, two grooves: a torus about the y axis, its tube pinched in two cross-sections, at +x
# and at -x. The rings are drawn off the grooves for a fit to move.
carrier = torus(major_radius=2.0, minor_radius=0.5, major_segments=160, minor_segments=40, triangulate=true)

pos = input_position()
xyz = separate_xyz(vector=pos.vector)
x2 = float_math(operation=MULTIPLY, a=xyz.x, b=xyz.x)
z2 = float_math(operation=MULTIPLY, a=xyz.z, b=xyz.z)
rho2 = float_math(operation=ADD, a=x2.result, b=z2.result)
rho = float_math(operation=SQRT, a=rho2.result)
# Unit direction from the axis, and the vector from the tube's centre circle (radius 2).
ux = float_math(operation=DIVIDE, a=xyz.x, b=rho.result)
uz = float_math(operation=DIVIDE, a=xyz.z, b=rho.result)
cx = float_math(operation=MULTIPLY, a=ux.result, b=2.0)
cz = float_math(operation=MULTIPLY, a=uz.result, b=2.0)
tx = float_math(operation=SUBTRACT, a=xyz.x, b=cx.result)
tz = float_math(operation=SUBTRACT, a=xyz.z, b=cz.result)
uz2 = float_math(operation=MULTIPLY, a=uz.result, b=uz.result)

# --- Groove at +x: 1 at its bottom, 0 from 0.3 along the torus away ---
a_dx = float_math(operation=SUBTRACT, a=ux.result, b=1.0)
a_dx2 = float_math(operation=MULTIPLY, a=a_dx.result, b=a_dx.result)
a_d2 = float_math(operation=ADD, a=a_dx2.result, b=uz2.result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
a_pinch = float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result)

# --- Groove at -x ---
b_dx = float_math(operation=ADD, a=ux.result, b=1.0)
b_dx2 = float_math(operation=MULTIPLY, a=b_dx.result, b=b_dx.result)
b_d2 = float_math(operation=ADD, a=b_dx2.result, b=uz2.result)
b_t2 = float_math(operation=MULTIPLY, a=b_d2.result, b=44.444444)
b_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=b_t2.result).result, b=0.0)
b_pinch = float_math(operation=MULTIPLY, a=b_s.result, b=b_s.result)
pinch = float_math(operation=MAXIMUM, a=a_pinch.result, b=b_pinch.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="1.767767,0.000000,1.767767; 1.574723,-0.445503,1.574723; 1.253704,-0.445503,1.253704; 1.060660,0.000000,1.060660; 1.253704,0.445503,1.253704; 1.574723,0.445503,1.574723", normal="-0.719828,-0.016898,0.693946", label="ring_00")
ring_01 = spline_ring(geometry=ring_00.geometry, metric=surface.metric, points="0.487726,0.000000,2.451963; 0.434465,-0.445503,2.184204; 0.345896,-0.445503,1.738937; 0.292635,0.000000,1.471178; 0.345896,0.445503,1.738937; 0.434465,0.445503,2.184204", normal="-0.984052,-0.016898,0.177079", label="ring_01")
