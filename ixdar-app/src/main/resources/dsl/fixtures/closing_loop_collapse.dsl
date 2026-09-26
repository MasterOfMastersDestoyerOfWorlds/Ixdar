# Closing loop collapse: a zero arc whose sibling runs between the same two nodes, so the
# collapse closes that sibling into a loop at the survivor. Both of the loop's flanks keep
# boundary of their own -- each holds a chain of its own -- so embedding the loop on the
# surviving point would pinch off the cell the rest of the fan still has to arrive in.
# The cells nest inside the outermost chain, whose far side is off the decomposition.
# LCBK19 Def 6.2, operator (1).
carrier = mesh_disk(rings=4, angular_segments=12, radius=4.0, triangulate=true)
network = arc_network(mesh=carrier.mesh)
survivorNodeId = network_node(net=network.net, point=<0.0, 0.0, 0.0>, critical=true)
movedNodeId = network_node(net=network.net, point=<0.0, 0.0, 2.0>)
innerTipNodeId = network_node(net=network.net, point=<1.7320508, 0.0, 1.0>)
outerTipNodeId = network_node(net=network.net, point=<0.0, 0.0, -2.0>)
zeroArcId = network_arc(net=network.net, from=survivorNodeId.id, to=movedNodeId.id)
loopArcId = network_arc(net=network.net, from=survivorNodeId.id, to=movedNodeId.id, via1=<2.598076, 0.0, -1.5>, via2=<2.598076, 0.0, 1.5>, via3=<1.5, 0.0, 2.598076>)
innerSpokeArcId = network_arc(net=network.net, from=survivorNodeId.id, to=innerTipNodeId.id, length=1)
innerSealArcId = network_arc(net=network.net, from=innerTipNodeId.id, to=movedNodeId.id, length=1)
outerSpokeArcId = network_arc(net=network.net, from=survivorNodeId.id, to=outerTipNodeId.id, length=1)
outerSealArcId = network_arc(net=network.net, from=outerTipNodeId.id, to=movedNodeId.id, length=1, via1=<2.0, 0.0, -3.4641016>, via2=<4.0, 0.0, 0.0>, via3=<2.0, 0.0, 3.4641016>, via4=<0.0, 0.0, 3.0>)
channelCellPatchId = network_patch(net=network.net, a=survivorNodeId.id, b=innerTipNodeId.id, c=innerTipNodeId.id, d=movedNodeId.id, first_side=1, second_side=0, third_side=1, fourth_side=1)
innerCellPatchId = network_patch(net=network.net, a=survivorNodeId.id, b=movedNodeId.id, c=innerTipNodeId.id, d=innerTipNodeId.id, first_side=1, second_side=1, third_side=0, fourth_side=1)
outerCellPatchId = network_patch(net=network.net, a=movedNodeId.id, b=survivorNodeId.id, c=outerTipNodeId.id, d=outerTipNodeId.id, first_side=1, second_side=1, third_side=0, fourth_side=1)
