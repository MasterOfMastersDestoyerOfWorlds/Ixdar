# MESH-77 / PATCH-127: the skull's hull chain (silhouette_hull_skull.dsl without the quad layout).
# Pleats of sliver faces folded back against the surface broke the quad layout at some resolutions;
# SilhouetteHullNodeTest (tagged heavy) overrides hull.resolution and hull.misses and asserts no face folds.
hull = silhouette_hull(svg="dsl/silhouette_hull_skull/outlines.svg", resolution=48, misses=0)
surface = volume_to_mesh(volume=hull.volume)
part = keep_largest_part(geometry=surface.geometry)
smooth = smooth_mesh(geometry=part.geometry)
