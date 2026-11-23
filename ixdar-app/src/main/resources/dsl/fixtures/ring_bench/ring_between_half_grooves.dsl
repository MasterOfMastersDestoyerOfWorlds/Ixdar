# Ring fit, straight ring between two half grooves: a torus about the y axis with a ring drawn
# straight round the tube at +x. 15 degrees round toward +z a groove pinches only the upper half
# of the tube; 15 degrees round toward -z a groove pinches only the lower half. A fit should move
# the ring's upper half into the first and its lower half into the second, the two joined across
# the outside and the inside of the tube, where neither groove reaches.
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
dx = float_math(operation=SUBTRACT, a=ux.result, b=0.965926)
dx2 = float_math(operation=MULTIPLY, a=dx.result, b=dx.result)

# --- Which half of the tube: 1 over the upper half, 0 over the lower, and the other way round;
# each rises from 0 at the outside and the inside of the tube to 1 within a row of faces ---
n_y = float_math(operation=MULTIPLY, a=xyz.y, b=2.0)
upper = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=n_y.result, b=6.666667).result, b=0.0).result, b=1.0)
lower = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=n_y.result, b=-6.666667).result, b=0.0).result, b=1.0)

# --- Groove 15 degrees round from +x toward -z, lower half: 1 at its bottom, 0 from 0.15 away ---
a_dz = float_math(operation=ADD, a=uz.result, b=0.258819)
a_dz2 = float_math(operation=MULTIPLY, a=a_dz.result, b=a_dz.result)
a_d2 = float_math(operation=ADD, a=dx2.result, b=a_dz2.result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
a_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result).result, b=lower.result)

# --- Groove 15 degrees round from +x toward +z, upper half ---
b_dz = float_math(operation=SUBTRACT, a=uz.result, b=0.258819)
b_dz2 = float_math(operation=MULTIPLY, a=b_dz.result, b=b_dz.result)
b_d2 = float_math(operation=ADD, a=dx2.result, b=b_dz2.result)
b_t2 = float_math(operation=MULTIPLY, a=b_d2.result, b=44.444444)
b_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=b_t2.result).result, b=0.0)
b_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=b_s.result, b=b_s.result).result, b=upper.result)
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
# At +x: midway between the half grooves.
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="2.500000,0.000000,0.000000; 2.226990,-0.445503,0.000000; 1.773010,-0.445503,0.000000; 1.500000,0.000000,0.000000; 1.773010,0.445503,0.000000; 2.226990,0.445503,0.000000", normal="0.000000,-0.016898,0.999857", label="ring_00")
