# Ring fit, one ring nearest both grooves: a torus about the y axis, its tube pinched in two
# cross-sections 60 degrees apart, at +x and a sixth of the way round toward +z. One ring is
# drawn midway between them and so is the nearer ring to both; the other is drawn far round the
# torus. A fit should leave one ring in each groove.
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

# --- Groove at +x: 1 at its bottom, 0 from 0.15 along the unit circle away ---
a_dx = float_math(operation=SUBTRACT, a=ux.result, b=1.0)
a_dx2 = float_math(operation=MULTIPLY, a=a_dx.result, b=a_dx.result)
a_dz2 = float_math(operation=MULTIPLY, a=uz.result, b=uz.result)
a_d2 = float_math(operation=ADD, a=a_dx2.result, b=a_dz2.result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
a_pinch = float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result)

# --- Groove 60 degrees round from +x toward +z ---
b_dx = float_math(operation=SUBTRACT, a=ux.result, b=0.5)
b_dz = float_math(operation=SUBTRACT, a=uz.result, b=0.866025)
b_dx2 = float_math(operation=MULTIPLY, a=b_dx.result, b=b_dx.result)
b_dz2 = float_math(operation=MULTIPLY, a=b_dz.result, b=b_dz.result)
b_d2 = float_math(operation=ADD, a=b_dx2.result, b=b_dz2.result)
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
# 30 degrees round: midway between the grooves.
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="2.165064,0.000000,1.250000; 1.928634,-0.445503,1.113498; 1.535467,-0.445503,0.886502; 1.299038,0.000000,0.750000; 1.535467,0.445503,0.886502; 1.928634,0.445503,1.113498", normal="-0.499928,-0.016898,0.865902", label="ring_00")
# 200 degrees round: 140 from the nearer groove.
ring_01 = spline_ring(geometry=ring_00.geometry, metric=surface.metric, points="-2.349232,0.000000,-0.855050; -2.092691,-0.445503,-0.761677; -1.666079,-0.445503,-0.606403; -1.409539,0.000000,-0.513030; -1.666079,0.445503,-0.606403; -2.092691,0.445503,-0.761677", normal="0.341971,-0.016898,-0.939558", label="ring_01")
