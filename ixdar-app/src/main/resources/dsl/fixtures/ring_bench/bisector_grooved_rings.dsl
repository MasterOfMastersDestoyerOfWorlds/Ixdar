# Ring fit, bisector, grooved rings: a torus about the y axis with one groove running all the way
# round its outside and two across its tube, at +z and at -x. A ring stands in each groove across;
# the bisector joining them is drawn beside the groove along the outside; a fit should move it
# into the groove, ends and all.
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
l_pinch = float_math(operation=MULTIPLY, a=l_s.result, b=l_s.result)

# --- Groove across the tube at +z: 0 from 0.3 along the torus away ---
a_dz = float_math(operation=SUBTRACT, a=uz.result, b=1.0)
a_dz2 = float_math(operation=MULTIPLY, a=a_dz.result, b=a_dz.result)
a_d2 = float_math(operation=ADD, a=a_dz2.result, b=float_math(operation=MULTIPLY, a=ux.result, b=ux.result).result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
a_pinch = float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result)

# --- Groove across the tube at -x ---
b_dx = float_math(operation=ADD, a=ux.result, b=1.0)
b_dx2 = float_math(operation=MULTIPLY, a=b_dx.result, b=b_dx.result)
b_d2 = float_math(operation=ADD, a=b_dx2.result, b=float_math(operation=MULTIPLY, a=uz.result, b=uz.result).result)
b_t2 = float_math(operation=MULTIPLY, a=b_d2.result, b=44.444444)
b_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=b_t2.result).result, b=0.0)
b_pinch = float_math(operation=MULTIPLY, a=b_s.result, b=b_s.result)
across = float_math(operation=MAXIMUM, a=a_pinch.result, b=b_pinch.result)
pinch = float_math(operation=MAXIMUM, a=l_pinch.result, b=across.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="0.000000,0.000000,2.350000; 0.000000,0.311852,2.158897; 0.000000,0.311852,1.841103; 0.000000,-0.311852,1.841103; 0.000000,-0.311852,2.158897", normal="0.981626,-0.035999,-0.187387", label="ring_00")
ring_01 = spline_ring(geometry=ring_00.geometry, metric=surface.metric, points="-2.350000,0.000000,0.000000; -2.158897,0.311852,0.000000; -1.841103,0.311852,0.000000; -1.841103,-0.311852,0.000000; -2.158897,-0.311852,0.000000", normal="0.187387,-0.035999,0.981626", label="ring_01")
ring_02 = spline_cut(geometry=ring_01.geometry, metric=surface.metric, points="0.000000,0.345691,2.054752; -0.824495,0.475528,1.990506; -1.523468,0.475528,1.523468; -1.990506,0.475528,0.824495; -2.054752,0.345691,0.000000", ends="ring_00; ring_01", label="ring_02")
