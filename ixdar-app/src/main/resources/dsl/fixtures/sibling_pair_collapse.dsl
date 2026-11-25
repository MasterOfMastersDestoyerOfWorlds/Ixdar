# Sibling pair collapse (crawfish IMG_4114): the surviving node's only spoke is the zero arc,
# and two siblings run from the moving node to the same far node around it, with a third arc
# between them. The first sibling's drag takes the short way onto the survivor; the second
# must arrive on the far side of the zero arc's spoke, where its departure corner lies. The
# north-west cell is walled by the first sibling, the zero arc, the west arc, the north arc
# and the grid border, so arriving on the wrong side of the zero arc finds no route.
# LCBK19 Section 6.1, operator (1).
carrier = mesh_grid(u_tiles=8, v_tiles=6, triangulate=true)
network = arc_network(mesh=carrier.mesh)
survivorNodeId = network_node(net=network.net, point=<1.0, 0.0, 0.0>, critical=true)
movedNodeId = network_node(net=network.net, point=<-1.0, 0.0, 0.0>)
farNodeId = network_node(net=network.net, point=<1.0, 0.0, 2.0>)
westNodeId = network_node(net=network.net, point=<-4.0, 0.0, 0.0>)
northNodeId = network_node(net=network.net, point=<1.0, 0.0, 3.0>)
zeroArcId = network_arc(net=network.net, from=movedNodeId.id, to=survivorNodeId.id)
northSiblingArcId = network_arc(net=network.net, from=movedNodeId.id, to=farNodeId.id, length=1, via1=<-1.0, 0.0, 2.0>)
southSiblingArcId = network_arc(net=network.net, from=movedNodeId.id, to=farNodeId.id, length=1, via1=<-1.0, 0.0, -2.0>, via2=<3.0, 0.0, -2.0>, via3=<3.0, 0.0, 2.0>)
westArcId = network_arc(net=network.net, from=movedNodeId.id, to=westNodeId.id, length=1)
northArcId = network_arc(net=network.net, from=farNodeId.id, to=northNodeId.id, length=1)
