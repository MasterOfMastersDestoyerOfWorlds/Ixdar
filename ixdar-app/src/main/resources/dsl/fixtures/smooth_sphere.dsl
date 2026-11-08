# Twenty passes of Taubin smoothing (lambda 0.5, mu -0.53) over a unit icosphere: the sphere must
# keep its radius, its vertices and its closed surface.
ball = icosphere(radius=1.0, subdivisions=3)
smooth = smooth_mesh(geometry=ball.mesh, passes=20)
