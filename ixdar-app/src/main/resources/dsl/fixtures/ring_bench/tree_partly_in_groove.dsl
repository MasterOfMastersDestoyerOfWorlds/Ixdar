# Ring fit, a branching groove: a torus about the y axis with a tree of grooves at +x. The trunk
# runs round the outer half of the tube. Where it reaches the top and the bottom of the tube it
# forks into two branches that run on toward the inside and end short of it: one leans a little
# toward +z, the other further toward -z. No two branch ends meet, so the tree closes no loop.
# The ring's outer half is drawn in the trunk. Its inner half is drawn between the branches, off
# both bottoms and nearer the one toward +z, and crosses the bare inside of the tube.
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

# --- Where a point stands round the tube: 1 on its outside, 0 at its top and bottom, -1 inside ---
n_r = float_math(operation=MULTIPLY, a=float_math(operation=SUBTRACT, a=rho.result, b=2.0).result, b=2.0)
# How far past the top or bottom toward the inside: 0 on the outer half, 1 on the inside.
inward = float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=n_r.result, b=-1.0).result, b=0.0)
# 1 on the +x side of the torus, so nothing below is repeated at -x.
near_x = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=ux.result, b=10.0).result, b=0.0).result, b=1.0)
# 1 on the outer half of the tube, falling to 0 just past its top and bottom.
on_trunk = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=ADD, a=float_math(operation=MULTIPLY, a=n_r.result, b=10.0).result, b=1.0).result, b=0.0).result, b=1.0)
# 1 from the top and bottom inward, falling to 0 between 0.75 and 0.85 of the way to the inside.
past_fork = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=float_math(operation=MULTIPLY, a=n_r.result, b=10.0).result).result, b=0.0).result, b=1.0)
before_end = float_math(operation=MINIMUM, a=float_math(operation=MAXIMUM, a=float_math(operation=MULTIPLY, a=float_math(operation=SUBTRACT, a=0.85, b=inward.result).result, b=10.0).result, b=0.0).result, b=1.0)
on_branch = float_math(operation=MULTIPLY, a=past_fork.result, b=before_end.result)

# --- Trunk: along +x, 1 at its bottom, 0 from 0.15 along the unit circle away ---
t_t2 = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=uz.result, b=uz.result).result, b=44.444444)
t_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=t_t2.result).result, b=0.0)
t_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=t_s.result, b=t_s.result).result, b=on_trunk.result)

# --- Near branch: leans toward +z by 0.20 for each unit inward ---
p_d = float_math(operation=SUBTRACT, a=uz.result, b=float_math(operation=MULTIPLY, a=inward.result, b=0.20).result)
p_t2 = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=p_d.result, b=p_d.result).result, b=44.444444)
p_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=p_t2.result).result, b=0.0)
p_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=p_s.result, b=p_s.result).result, b=on_branch.result)

# --- Far branch: leans toward -z by 0.35 for each unit inward ---
q_d = float_math(operation=ADD, a=uz.result, b=float_math(operation=MULTIPLY, a=inward.result, b=0.35).result)
q_t2 = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=q_d.result, b=q_d.result).result, b=44.444444)
q_s = float_math(operation=MAXIMUM, a=float_math(operation=SUBTRACT, a=1.0, b=q_t2.result).result, b=0.0)
q_pinch = float_math(operation=MULTIPLY, a=float_math(operation=MULTIPLY, a=q_s.result, b=q_s.result).result, b=on_branch.result)

branches = float_math(operation=MAXIMUM, a=p_pinch.result, b=q_pinch.result)
tree = float_math(operation=MAXIMUM, a=t_pinch.result, b=branches.result)
pinch = float_math(operation=MULTIPLY, a=tree.result, b=near_x.result)

# --- Pinch the tube toward its centre circle by up to 0.3 of its radius ---
depth = float_math(operation=MULTIPLY, a=pinch.result, b=-0.3)
disp_x = float_math(operation=MULTIPLY, a=tx.result, b=depth.result)
disp_y = float_math(operation=MULTIPLY, a=xyz.y, b=depth.result)
disp_z = float_math(operation=MULTIPLY, a=tz.result, b=depth.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
grooved = set_position(geometry=carrier.mesh, offset=displacement.vector)
surface = surface_metric(geometry=grooved.geometry)
creases = surface_creases(geometry=surface.geometry)
# Round the tube from its outside: three anchors in the trunk, then five at 2.25 degrees toward
# +z. Beside the branches the near branch's bottom is 3 and 6 degrees further that way, the far
# branch's 11 and 17 degrees the other way.
ring_00 = spline_ring(geometry=creases.geometry, metric=surface.metric, points="2.500000,0.000000,0.000000; 2.226995,-0.445503,0.000000; 1.771638,-0.445503,0.069608; 1.645177,-0.353553,0.064639; 1.498844,0.000000,0.058890; 1.645177,0.353553,0.064639; 1.771638,0.445503,0.069608; 2.226995,0.445503,0.000000", normal="0.000000,-0.016898,0.999857", label="ring_00")
