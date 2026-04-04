# Tube along a grid polyline using curve_sweep (profile = first face of section mesh).
section = mesh_grid(u_tiles=1, v_tiles=1, u_tile_size=0.15, v_tile_size=0.15)
path_src = mesh_grid(u_tiles=12, v_tiles=1, u_tile_size=0.25, v_tile_size=0.04)
path_curve = mesh_to_curve(geometry=path_src.mesh, source=BOUNDARY)
path_fine = resample_curve(curve=path_curve.curve, length=0.07)
tube = curve_sweep(curve=path_fine.curve, profile=section.mesh, caps=true)
