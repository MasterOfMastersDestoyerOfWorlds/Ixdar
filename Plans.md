how to deal with a three match:

the following situation often arises, we find the minKnot which contains one of the cut segments and not the other, so far so good, but the other cut segment is not on the boundary of our minknot. 

So we have two routes out of the minknot  one leading to the upper knot point and the other leading to the upper cut segment, how should we handle this situation to most effectively find the best route?

I think we need to update the find minknot and expand minknot functions to expand along one of the cut segments if both cut segments are on the border of the minknot, we then simple match across the new hole, then we should always have a knot that can be  fix cut in two matches at most.

sorry thiS isn't quite right ^^

it isn't right because this isn't the situation in which the three match code is run. What we actually need to do is the same thing I described, inch along the exposed path that leads out of the knot toward the cutSegment, more specifically toward the side of the cut segment that contains the upper cut point, then match diagonally across to the  lower knot point? anyway the point on the other side that is leading out toward the upper knot point, this new knot is your new min knot and only needs one math with the neighbor.

But is this following the rules we set out for ourselves? at some level this would break the natural abstraction of the knot since we are dividing the upper containing knot in half without there being a natural division line there. not good!

So does that mean that we need to solve for the three knot match? and also does that mean that we need to try our best to make the vestigial branch (leading from our minknot to the cut point) into a knot if one is available?

ree

I also have doubts that the neighbor as the closest specific point in the chain next to the minKnot is really always the best to use as the external. this seems like it would always be the case in the plane, but as we leave the confines of the 2nd dimension this becomes less true. It is certainly the case that the path between the upper knotpoint and the neighbor is optimal one with those two points as the endpoints, and there is a natural division between the two groups, but only the knotpoint is necessarily fixed because it us connecting to an external, the neighbor could be replaced by any of the other points along the path with some rejiggering of the path. what would be an alternative? is there an alternative that does not resort to exhaustive search?

i also feel like there is a reason why the neighbor works so well, and i think it has something to do with that the neighbor is already the lowest cost match to the minknot group and unless we lived in a non-metric space(we are in metric space by definition), then this will remain true for any other point we match it with the neighbor (cope and seethe)


I think cutting the knot in half is fine cause the lower knot will take care of the mess

the path from lower knot point to upper cut point would be optimal except for the connector between the two, though in the plane it also seems to be optimal but idk  

so should we redefine the minknot as the maximal knot that has one cut segment internally and borders the other one? 