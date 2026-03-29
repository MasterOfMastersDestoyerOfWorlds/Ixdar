# Coons patch cube — each face is a bilinearly blended patch over cubic bezier edges
subdivisions = input_int(name="subdivisions", default=8, min=1, max=8)
handle_weight = input_float(name="handle_weight", default=0.333, min=0.0, max=2.0)
base_cube = cube(size=1.0)
with_handles = assign_bezier_handles(geometry=base_cube.mesh, weight=handle_weight.result)
patch_out = coons_patch(geometry=with_handles.geometry, subdivisions=subdivisions.result)
