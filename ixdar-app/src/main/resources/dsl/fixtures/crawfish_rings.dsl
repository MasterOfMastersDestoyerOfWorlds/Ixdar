# CRAW-26 -- one Trellis2 crawfish scan as repair_mesh leaves it.
#
# The scans live outside the repository (/home/acw/crawfish/IMG_4109.glb .. IMG_4118.glb); the
# glTF loader already welds bitwise-identical positions, so weld_epsilon stays at zero and this
# graph only exercises the topological stages. Trellis closes the back surface badly rather than
# leaving it open, so every boundary loop here is a scanning artefact and max_hole_edges is only a
# safety cap: the repaired scan has no boundary left, and the `open_boundary` edge-marks overlay
# the mesh viewer draws is empty.
scan = load_mesh(path="/home/acw/crawfish/IMG_4109.glb")
repaired = repair_mesh(geometry=scan.geometry, weld_epsilon=0.0, max_hole_edges=4096, min_shell_faces=100, strict=false)
ring_00 = spline_ring(geometry=repaired.geometry, points="0.232332,0.014114,0.069664; 0.211900,0.021999,0.080991; 0.195528,0.029212,0.093261; 0.187356,0.032999,0.096550; 0.175938,0.035443,0.100973; 0.160587,0.038894,0.112941; 0.148171,0.024174,0.092227; 0.127676,-0.004114,0.075878; 0.141902,-0.025401,0.052903; 0.158178,-0.037287,0.041662; 0.188543,-0.038309,0.037488; 0.227451,-0.009832,0.049488", normal="-0.364105,-0.131871,0.921975", label="ring_00")
ring_01 = spline_ring(geometry=ring_00.geometry, points="-0.239132,-0.122137,-0.000756", normal="-0.541013,-0.539631,-0.645060", label="ring_01")
ring_03 = spline_ring(geometry=ring_01.geometry, points="-0.018937,0.030889,-0.400471; -0.040045,-0.018626,-0.374912; -0.075667,-0.011692,-0.370267; -0.101075,0.028312,-0.369253; -0.105851,-0.011657,-0.355791; -0.151434,0.017201,-0.312632; 0.069827,-0.025375,-0.362289; 0.034204,-0.016891,-0.376336; 0.086815,0.020203,-0.366193; 0.094815,-0.024883,-0.350686; 0.122866,0.001180,-0.332544; 0.030724,0.029828,-0.399411; -0.013219,-0.013873,-0.376563", normal="-0.832898,-0.356410,-0.423382", label="ring_03")
ring_04 = spline_ring(geometry=ring_03.geometry, points="0.123649,0.009073,0.212624; 0.091033,-0.033669,0.209937; 0.104600,-0.077468,0.237649; 0.147443,-0.083212,0.247608; 0.131866,-0.045116,0.250695; 0.126188,-0.037101,0.240807; 0.128426,0.005877,0.219706", normal="0.013089,0.962209,-0.271996", label="ring_04")
ring_05 = spline_ring(geometry=ring_04.geometry, points="-0.122493,0.039080,0.226560; -0.131413,-0.060890,0.238470; -0.127563,-0.054250,0.201261; -0.088280,0.003681,0.219927; -0.074274,0.027537,0.215187; -0.092568,0.035920,0.220422", normal="-0.186573,-0.839413,0.510466", label="ring_05")
ring_06 = spline_ring(geometry=ring_05.geometry, points="-0.186160,0.031173,0.120614; -0.111231,0.001546,0.087052; -0.117104,0.018208,0.103986; -0.139505,0.030968,0.116062; -0.152336,0.045881,0.129779", normal="-0.306259,-0.219771,0.926233", label="ring_06")
ring_07 = spline_ring(geometry=ring_06.geometry, points="-0.025400,-0.031313,0.442907; -0.033278,-0.037984,0.436272; -0.028153,-0.036203,0.426700; -0.019568,-0.031415,0.439053; -0.020935,-0.032405,0.441424", normal="-0.431423,-0.630605,-0.645144", label="ring_07")
ring_08 = spline_ring(geometry=ring_07.geometry, points="-0.015818,0.104873,-0.012973", normal="-0.377827,-0.326244,-0.866494", label="ring_08")
ring_09 = spline_ring(geometry=ring_08.geometry, points="-0.009787,0.045013,-0.000670", normal="0.000502,-0.233347,-0.972394", label="ring_09")
ring_10 = spline_ring(geometry=ring_09.geometry, points="0.044701,0.045788,0.006390", normal="0.023564,-0.470920,-0.881861", label="ring_10")
ring_11 = spline_ring(geometry=ring_10.geometry, points="0.047977,0.114686,-0.020719; 0.045699,0.100236,-0.018033; 0.035198,0.094640,-0.019934; 0.029671,0.108987,-0.030271; 0.033271,0.113757,-0.026764; 0.038106,0.116024,-0.020516", normal="-0.599352,0.475162,0.644203", label="ring_11")
ring_12 = spline_ring(geometry=ring_11.geometry, points="0.024872,0.069239,0.028454", normal="-0.953658,-0.218056,-0.207338", label="ring_12")
