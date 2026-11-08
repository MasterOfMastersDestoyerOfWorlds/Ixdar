# A unit sphere as the hull of three unit circles, left unrounded so volume_to_mesh sees the carve
# itself; the surface must close with a sphere's topology and keep a radius of 1.
hull = silhouette_hull(svg="dsl/fixtures/silhouette_hull/sphere.svg", resolution=24, misses=0, rounding=0.0)
surface = volume_to_mesh(volume=hull.volume)
