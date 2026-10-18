List of avaiable moves:
[x] create a knot by adding two points together
[x] grow a knot by a point
[x] create a knot by adding two knots together in a pipe fashion
[ ] grow a knot by a knot cut on both sides
[ ] collapse a knot path so that two paths become one

missing capabilities:
[x] need to be able to tell if two knots are in the same group use the union find algorithm
[x] should not be able to pipe between two knots in the same group since this will always cause multiple cycles in the final result
[x] need to only apply a change when both groups agree that it is the correct change
[ ] need to only consider the right resolution of the knot so if ou kave k3 [k1 <- s1 & s2 -> k2] then if you are looking at k3 then you should only "see" the segments s1 and s2, any of the segments in k1 or k2 you should have to go and look at k1 or k2 to cut
[ ] when piping between two knots you should not make an uber knot each time adding to a growing knot, instead make a knot for each pipe in itself and then, only make an uber knot when collapsing all of the smaller knots
[ ] need to be able to tell which slots in the knot manifold have been taken by a pipe

Drawing:
[ ] need to change how we are drawing the knots cause if you have a chain k1 <=> k2 <=> k3 and we draw k4[k1 <=> k2] and then k5[k2 <=> k3], k4 will draw the part of k2 it didnt cut and k5 will draw the part of k2 it didnt cut, and k2 will be a complete circle instead of a circle with two segments removed.
[ ] maybe need to keep track of every cutmatch that has happened to a knot to get to its final form so that we can replay the stack of every knot below the look layer in order to get the manifold

Optimization:
[ ] need to save all of the calculated moves, if nothing changes then most of the calculation is wasted.  