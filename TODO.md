## TODO

### Algorithm Changes

1. - [ ] New Algorithm to search space within Knot until Knot boundary
2. - [ ] Should only move toward the exit in terms of Knot distance 
3. - [ ] should also have the ability to change the previous Knot boundary's connection
4. - [ ] need to change cutKnot method to include

### Unit Testing

1. - [x] Add Button to generate manifold tests and solutions in new unit test file (VK_G)
2. - [x] Should generate one test file per cut segment pair 
3. - [x] Should also generate one master manifold file that contains all of the cut segment pairs
4. - [ ] Generate a Unit Test that runs all of the manifold solutions in the folder
5. - [ ] Should compare against the manifold cut match answer in the master file

### Filesystem

1. - [x] When we are in manifold mode update key (VK_U) should update the cut match CUTANS in the respective file.
2. - [x] Should only update when we have the correct answer length and the cutmatch is smaller than the one stored in the file
3. - [x] Manifold files should have the following format djbouti-8-34-manifold_KP1-CP1_KP2-CP2
4. - [x] Should be able to load multiple manifolds per file

### UI CutMatch Tool

1. - [ ] Make tool to test out new cut match groups. 
2. - [x] Figure out screenspace to pointspace conversion
3. - [x] Figure out Skewed bounding boxes around Line Segments
4. - [ ] Should follow pointer with a cyan line and display a yellow line on the nearest cut segment to the pointer. 
5. - [ ] Should highlight a segment when within the circle that's center is in the midpoint of the two segments and has a radius of the distance between the midpoint and one of the ends. (or some other method)
6. - [ ] The original calculated cut match group should be at like opacity 40  or 30. 
7. - [ ] Once the user clicks on a highlighted cutmatch pair add that cutmatch to the the edited cutmatch list
8. - [ ] Don't end this state till either the edited cutmatch group finds cutpoint2 or the user presses the hotkey again.
9. - [ ] User should not be able to make invalid cuts and there should be a separate hotkey to undo a cutmatch. 
10. - [ ] Should also have a starting state tool that chooses where on the original cutmatch group to start from.
11. - [ ] Starting tool should set all cut matches before the pointer to opacity 100 and the ones after to opacity 40

### UI
1. - [ ] Should have some panel on the right hand side above logo with name of test set, wether the test set is passing, difference in length between test set answer and calculated answer.
2. - [ ] VK_O should swap between showing the calculated cutmatch and the one stored in the file and use opposite colors on the color wheel to represent the cutmatch?
3. - [ ] CTRL + VK_F in manifold mode should start a two step process where you click to select two knot points in the manifold.
4. - [x] VK_B should cycle the manifold index

### Bugfixes
1. - [ ] Need to check overlapping cut segments with two knotPoints and one cutPoint, but continue to skip overlapping cut segments with one knotPoint and two cutPoints.
2. - [ ] Need to Disallow making knots with all internals being knots where the first knot and the last knot do not actually want to match to each other. this is fine when everything is a point, but the correct way to represent this would be : Knot[Knot[Knot[1] Knot[2]] Knot[3]] instead of Knot[Knot[1] Knot[2] Knot[3]]


### General Speedup
1. - [x]  Remove repeated segment pairs from the main loop (2x speedup)
2. - [ ]  Add worker pool for every dijkstra's call we make (algorithm is somewhat embarrassingly parallel)
3. - [x]  Remove redundant error handling
4. - [ ]  Figure out how to turn into positive weight graph so we can disregard all settled Points.
5. - [ ]  Find some way to calculate all shortest paths for a manifold at once instead of in series (seems unlikely given path dependence)
6. - [x]  For less accuracy dependent problems could use heuristic of distance to KnotPoints from externals plus distance between CutPoints as best measure of where to calculate internal structure changing from N^7 to N^3 operation.