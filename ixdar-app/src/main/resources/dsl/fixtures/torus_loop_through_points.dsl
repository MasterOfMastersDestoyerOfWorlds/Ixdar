carrier = torus(major_radius=1.0, minor_radius=0.35, major_segments=64, minor_segments=48, triangulate=true)
seed = loop_through_points(geometry=carrier.mesh, tighten=false, label="ring_seed", points="1.350000,0.000000,0.000000; 0.445749,0.303109,0.694214; 0.512828,-0.303109,-0.646245")
ring = loop_through_points(geometry=seed.geometry, label="ring_tightened", points="1.350000,0.000000,0.000000; 0.445749,0.303109,0.694214; 0.512828,-0.303109,-0.646245")
