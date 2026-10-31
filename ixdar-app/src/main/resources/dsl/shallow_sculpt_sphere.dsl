# Shallow sculpt sphere: a unit-diameter sphere with features a few percent of its radius deep,
# for judging how well a shading mode reads shallow relief.
# Features: a nose ridge on +Z, two eye sockets above it, and an equatorial crease all round.

raw = sphere(size=1.0, resolution=256)
# The primitive emits each quad with its own corners; welding gives smooth vertex normals.
base = merge_by_distance(geometry=raw.mesh, distance=0.0001)

pos = input_position()
xyz = separate_xyz(vector=pos.vector)
# Unit direction components (the sphere's radius is 0.5).
ux = float_math(operation=MULTIPLY, a=xyz.x, b=2.0)
uy = float_math(operation=MULTIPLY, a=xyz.y, b=2.0)
uz = float_math(operation=MULTIPLY, a=xyz.z, b=2.0)
front = float_math(operation=MAXIMUM, a=uz.result, b=0.0)

# --- Nose ridge: narrow in x, long in y, front hemisphere only ---
nose_x = float_math(operation=DIVIDE, a=ux.result, b=0.12)
nose_y_off = float_math(operation=ADD, a=uy.result, b=0.05)
nose_y = float_math(operation=DIVIDE, a=nose_y_off.result, b=0.32)
nose_x2 = float_math(operation=MULTIPLY, a=nose_x.result, b=nose_x.result)
nose_y2 = float_math(operation=MULTIPLY, a=nose_y.result, b=nose_y.result)
nose_t2 = float_math(operation=ADD, a=nose_x2.result, b=nose_y2.result)
nose_s_raw = float_math(operation=SUBTRACT, a=1.0, b=nose_t2.result)
nose_s = float_math(operation=MAXIMUM, a=nose_s_raw.result, b=0.0)
nose_k = float_math(operation=MULTIPLY, a=nose_s.result, b=nose_s.result)
nose_front = float_math(operation=MULTIPLY, a=nose_k.result, b=front.result)
nose = float_math(operation=MULTIPLY, a=nose_front.result, b=0.022)

# --- Eye sockets: round dimples at (+-0.3, 0.28) on the front ---
eye_ax = float_math(operation=ABSOLUTE, a=ux.result)
eye_dx_raw = float_math(operation=SUBTRACT, a=eye_ax.result, b=0.3)
eye_dx = float_math(operation=DIVIDE, a=eye_dx_raw.result, b=0.16)
eye_dy_raw = float_math(operation=SUBTRACT, a=uy.result, b=0.28)
eye_dy = float_math(operation=DIVIDE, a=eye_dy_raw.result, b=0.16)
eye_dx2 = float_math(operation=MULTIPLY, a=eye_dx.result, b=eye_dx.result)
eye_dy2 = float_math(operation=MULTIPLY, a=eye_dy.result, b=eye_dy.result)
eye_t2 = float_math(operation=ADD, a=eye_dx2.result, b=eye_dy2.result)
eye_s_raw = float_math(operation=SUBTRACT, a=1.0, b=eye_t2.result)
eye_s = float_math(operation=MAXIMUM, a=eye_s_raw.result, b=0.0)
eye_k = float_math(operation=MULTIPLY, a=eye_s.result, b=eye_s.result)
eye_front = float_math(operation=MULTIPLY, a=eye_k.result, b=front.result)
eyes = float_math(operation=MULTIPLY, a=eye_front.result, b=-0.016)

# --- Equatorial crease: a narrow groove round the whole sphere ---
crease_y = float_math(operation=DIVIDE, a=uy.result, b=0.06)
crease_y2 = float_math(operation=MULTIPLY, a=crease_y.result, b=crease_y.result)
crease_s_raw = float_math(operation=SUBTRACT, a=1.0, b=crease_y2.result)
crease_s = float_math(operation=MAXIMUM, a=crease_s_raw.result, b=0.0)
crease_k = float_math(operation=MULTIPLY, a=crease_s.result, b=crease_s.result)
crease = float_math(operation=MULTIPLY, a=crease_k.result, b=-0.007)

# --- Displace along the radial direction ---
face_relief = float_math(operation=ADD, a=nose.result, b=eyes.result)
relief = float_math(operation=ADD, a=face_relief.result, b=crease.result)
disp_x = float_math(operation=MULTIPLY, a=ux.result, b=relief.result)
disp_y = float_math(operation=MULTIPLY, a=uy.result, b=relief.result)
disp_z = float_math(operation=MULTIPLY, a=uz.result, b=relief.result)
displacement = combine_xyz(x=disp_x.result, y=disp_y.result, z=disp_z.result)
sculpted = set_position(geometry=base.geometry, offset=displacement.vector)
