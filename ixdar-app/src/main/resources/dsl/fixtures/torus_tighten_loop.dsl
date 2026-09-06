# CRAW-21: a wobbly seed loop around the torus tube, tightened to a geodesic meridian by FlipOut.
# The three waypoints sit at 0, 120 and 240 degrees around the tube but at three different angles
# the long way round, so the shortest-edge seed spirals and has to be straightened, not just
# smoothed. iterations=0 keeps the raw seed walk under its own edge-marks label, so the viewer
# draws the seed and the geodesic in contrasting colours over one carrier.
# The tightened loop measures 2.196 against 2.199 for the analytic meridian, 2*pi*0.35.
carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=64, minor_segments=48, triangulate=true)
seed = tighten_path(geometry=carrier.mesh, closed=true, iterations=0, label="a_seed_loop", path="1.350000,0.000000,0.000000; 0.445749,0.303109,0.694214; 0.512828,-0.303109,-0.646245")
tightened = tighten_path(geometry=seed.geometry, closed=true, label="b_tightened_loop", path="1.350000,0.000000,0.000000; 0.445749,0.303109,0.694214; 0.512828,-0.303109,-0.646245")
