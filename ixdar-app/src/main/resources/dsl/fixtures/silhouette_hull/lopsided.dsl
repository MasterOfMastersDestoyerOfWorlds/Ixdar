# MESH-80: the front trace bulges right (+x reaches 1.0, -x only 0.7) while the top and the side
# agree on a body symmetric about x = 0. As traced the hull is lopsided; with symmetry="x" it comes
# out symmetric. SilhouetteHullNodeTest overrides hull.symmetry to "none" for the traced variant.
hull = silhouette_hull(svg="dsl/fixtures/silhouette_hull/lopsided.svg", resolution=24, misses=0, symmetry="x")
surface = volume_to_mesh(volume=hull.volume)
