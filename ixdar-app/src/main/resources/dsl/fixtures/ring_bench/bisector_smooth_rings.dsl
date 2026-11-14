# Ring fit, bisector, smooth rings: a torus about the y axis with one groove running all the way
# round its outside. Two rings stand on smooth cross-sections, at +z and at -x; the bisector
# joining them is drawn beside the groove; a fit should move it into the groove, ends and all.
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

# --- Groove along the outside of the torus: 1 at its bottom, 0 from 0.3 round the tube away ---
l_in = float_math(operation=SUBTRACT, a=2.5, b=rho.result)
l_t2 = float_math(operation=MULTIPLY, a=l_in.result, b=11.111111)
l_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=l_t2.result).result, b=0.0)
pinch = float_math(operation=MULTIPLY, a=l_s.result, b=l_s.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="0.000000,0.000000,2.350000; 0.000000,0.445503,2.226995; 0.000000,0.445503,1.773005; 0.000000,0.000000,1.500000; 0.000000,-0.445503,1.773005; 0.000000,-0.445503,2.226995", normal="0.999810,-0.009078,0.017233", label="ring_00")
ring_01 = spline_ring(geometry=ring_00.geometry, metric=surface.metric, points="-2.350000,0.000000,0.000000; -2.226995,0.445503,0.000000; -1.773005,0.445503,0.000000; -1.500000,0.000000,0.000000; -1.773005,-0.445503,0.000000; -2.226995,-0.445503,0.000000", normal="-0.017233,-0.009078,0.999810", label="ring_01")
ring_02 = spline_cut(geometry=ring_01.geometry, metric=surface.metric, points="0.000000,0.475528,2.154509; -0.824495,0.475528,1.990506; -1.523468,0.475528,1.523468; -1.990506,0.475528,0.824495; -2.154509,0.475528,0.000000", ends="ring_00; ring_01", label="ring_02")
