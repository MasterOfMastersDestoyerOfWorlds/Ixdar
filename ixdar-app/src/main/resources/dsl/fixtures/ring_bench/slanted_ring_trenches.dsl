# Ring fit, slanted ring with trenches: the slanted-ring fixture, plus two trenches joining the
# two grooves, one along the outside of the tube and one along the inside, as deep as the grooves.
# The ring as drawn crosses from one groove to the other over the top and the bottom of the tube,
# where there is no trench.
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

# --- Groove 15 degrees round from +x toward -z: 1 at its bottom, 0 from 0.15 away ---
a_dz = float_math(operation=ADD, a=uz.result, b=0.258819)
a_dz2 = float_math(operation=MULTIPLY, a=a_dz.result, b=a_dz.result)
a_d2 = float_math(operation=ADD, a=dx2.result, b=a_dz2.result)
a_t2 = float_math(operation=MULTIPLY, a=a_d2.result, b=44.444444)
a_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=a_t2.result).result, b=0.0)
a_pinch = float_math(operation=MULTIPLY, a=a_s.result, b=a_s.result)

# --- Groove 15 degrees round from +x toward +z ---
b_dz = float_math(operation=SUBTRACT, a=uz.result, b=0.258819)
b_dz2 = float_math(operation=MULTIPLY, a=b_dz.result, b=b_dz.result)
b_d2 = float_math(operation=ADD, a=dx2.result, b=b_dz2.result)
b_t2 = float_math(operation=MULTIPLY, a=b_d2.result, b=44.444444)
b_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=b_t2.result).result, b=0.0)
b_pinch = float_math(operation=MULTIPLY, a=b_s.result, b=b_s.result)
# --- Where a point stands round the tube, as a unit vector: out from the axis, and up ---
n_r = float_math(operation=MULTIPLY, a=float_math(operation=SUBTRACT, a=rho.result, b=2.0).result, b=2.0)
n_y = float_math(operation=MULTIPLY, a=xyz.y, b=2.0)
n_y2 = float_math(operation=MULTIPLY, a=n_y.result, b=n_y.result)
# 1 between the grooves, falling to 0 just past each of them.
between = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=float_math(operation=SUBTRACT, a=ux.result, b=0.95).result, b=40.0).result, b=0.0).result, b=1.0)

# --- Trench along the outside of the tube: 1 at its bottom, 0 from 0.3 round the tube away ---
c_dr = float_math(operation=SUBTRACT, a=n_r.result, b=1.0)
c_d2 = float_math(operation=ADD, a=float_math(operation=MULTIPLY, a=c_dr.result, b=c_dr.result).result, b=n_y2.result)
c_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=MULTIPLY, a=c_d2.result, b=11.111111).result).result, b=0.0)
c_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=c_s.result, b=c_s.result).result, b=between.result)

# --- Trench along the inside of the tube ---
e_dr = float_math(operation=ADD, a=n_r.result, b=1.0)
e_d2 = float_math(operation=ADD, a=float_math(operation=MULTIPLY, a=e_dr.result, b=e_dr.result).result, b=n_y2.result)
e_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=MULTIPLY, a=e_d2.result, b=11.111111).result).result, b=0.0)
e_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=e_s.result, b=e_s.result).result, b=between.result)
trenches = float_math(operation=MAXIMUM, a=c_pinch.result, b=e_pinch.result)
grooves = float_math(operation=MAXIMUM, a=a_pinch.result, b=b_pinch.result)
pinch = float_math(operation=MAXIMUM, a=grooves.result, b=trenches.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="2.454068,0.010000,-0.477022; 2.186079,-0.445503,-0.424931; 1.740430,-0.445503,0.338305; 1.472441,0.010000,0.286213; 1.740430,0.445503,0.338305; 2.186079,0.445503,-0.424931", normal="-0.613727,-0.016898,-0.789337", label="ring_00")
