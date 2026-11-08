# A leaning egg: an ellipsoid whose z gains half its height. Its hull must lean with it.
hull = silhouette_hull(svg="dsl/fixtures/silhouette_hull/egg.svg", resolution=16, misses=1)
surface = volume_to_mesh(volume=hull.volume)
part = keep_largest_part(geometry=surface.geometry)
smooth = smooth_mesh(geometry=part.geometry)
