# Ring fit, one groove: a torus about the y axis, its tube pinched in one cross-section, at +x.
# A ring drawn nearly opposite the groove should still be fitted into it.
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

# --- Groove at +x: 1 at its bottom, 0 from 0.3 along the torus away ---
a_dx = float_math(operation=SUBTRACT, a=ux.result, b=1.0)
a_dx2 = float_math(operation=MULTIPLY, a=a_dx.result, b=a_dx.result)
a_d2 = float_math(operation=ADD, a=a_dx2.result, b=float_math(operation=MULTIPLY, a=uz.result, b=uz.result).result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
pinch = float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="-2.430925,0.000000,0.583613; -2.165463,-0.445503,0.519882; -1.724017,-0.445503,0.413900; -1.458555,0.000000,0.350168; -1.724017,0.445503,0.413900; -2.165463,0.445503,0.519882", normal="-0.215577,-0.016899,-0.976341", label="ring_00")
