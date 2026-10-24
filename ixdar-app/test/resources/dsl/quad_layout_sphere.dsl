# The quad layout on the smallest tracked closed mesh, sphere_base (254 vertices, genus zero): the
# light quad-mesh entry of the golden fingerprints, whose quads, T-mesh, motorcycle graph and
# embedded network all fingerprint in about a second.
tri = load_mesh(path="test/resources/quadlayout/figure_7/sphere_base_in_tri.off")
layout = quad_layout(geometry=tri.geometry)
