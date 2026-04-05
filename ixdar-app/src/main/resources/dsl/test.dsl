# Minimal test DSL
test_grid = mesh_grid(u_tiles=10, v_tiles=10, u_total_size=2.0, v_total_size=2.0)
output = assign_output(geometry=test_grid.mesh, name="test")