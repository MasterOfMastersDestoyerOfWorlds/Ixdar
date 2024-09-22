# TODO

## Algorithm Changes

1. - [ ] New Algorithm to search space within Knot until Knot boundary
2. - [ ] Should only move toward the exit in terms of Knot distance
3. - [ ] Should also have the ability to change the previous Knot boundary's connection
4. - [ ] Need to change cutKnot method to cut all knots on a level at once
5. - [ ] Need to check overlapping cut segments with two knotPoints and one cutPoint, but continue to skip overlapping cut segments with one knotPoint and two cutPoints.
6. - [ ] If we are simply moving through a knot (from one of the choke segments to the opposite one) we should be able to save this route somehow.
7. - [ ] Need to figure out the maximum number of moves within a knot so we can relax the constraints of where we can move and not have as much calculation.

## Knot Finding

1. - [ ] If we have a VirtualPoint A that points to two different internal VirtualPoints B and C in the top layer of a Knot, we need to insert A between B and C.
2. - [ ] Need to Disallow making knots with all internals being knots where the first knot and the last knot do not actually want to match to each other. this is fine when everything is a point, but the correct way to represent this would be : Knot[Knot[Knot[1] Knot[2]] Knot[3]] instead of Knot[Knot[1] Knot[2] Knot[3]]

## Unit Testing

1. - [x] Add Button to generate manifold tests and solutions in new unit test file (VK_G)
2. - [x] Should generate one test file per cut segment pair
3. - [x] Should also generate one master manifold file that contains all of the cut segment pairs
4. - [ ] Generate a Unit Test that runs all of the manifold solutions in the folder
5. - [ ] Should compare against the manifold cut match answer in the master file

## UI Tools General

1. - [ ] Tools should specify what type of bounding boxes that they should use? (Segment rectangle versus radius from point?)
2. - [ ] Each Tool should tell the user some information about what to do when using it in the message pool.
3. - [x] Should keep track of the current selected tool and not allow two tools to be active at once, all tools should only effect the ui's state once the final necessary selection is made
4. - [x] Need to have one side of the knotPoint/cutPoint pair follow the cursor instead of just defaulting to clockwise knotPoint.
5. - [x] Figure out screen-space to point-space conversion
6. - [x] Figure out Skewed bounding boxes around Line Segments
7. - [x] Should set a segment as the "hover segment" when within some distance to one of the line Segments in the current Manifold Knot.
8. - [x] Should expand the Segment Bounding boxes to work at any knot level not just the manifold knot.
9. - [x] Should only have one tool active at a time and call the super class's draw and click functions
10. - [x] Clicking and dragging should change the panX, panY to where the new mouse position is.
11. - [x] Pressing Up/Down Arrow Key should change the Knot Level unless blocked.
12. - [x] Pressing VK_R should reset the camera and reset the active tool but not exit it.
13. - [x] Pressing and Holding Left/Right should repeat the action at some reasonable rate.
14. - [x] Need to find some way to determine clockwise versus anti clockwise.
15. - [x] Pressing VK_Enter should advance the tool if there is a selection active

## UI Free Tool

1. - [x] Clicking on a point should mark it with a circle around the point and change the perma-hover to the selected VirtualPoint
2. - [ ] Hover over a point should display information about the VirtualPoint like, it's closest three segments and their distances, the x/y coordinates and the minimum knot the point is in.
3. - [x] Pressing Left/Right Arrow Key should move the selected point to the next clockwise point in the current knot level.
4. - [ ] Pressing VK_K should switch to displaying the same information about the current knot the point is in.
5. - [ ] Knot's should display their closest two segments with rotating dashed lines. (use dash phase).

## UI CutMatch Tool

1. - [x] Make tool to test out new cut match groups.
2. - [x] Should follow pointer with a cyan line and display a yellow line on the nearest cut segment to the pointer.
3. - [ ] The original calculated cut match group should be at like opacity 40 or 30.
4. - [ ] Once the user clicks on a highlighted cutMatch pair add that cutMatch to the the edited cutMatch list
5. - [ ] Don't end this state till either the edited cutMatch group finds cutPoint2 or the user presses the hotkey again.
6. - [ ] User should not be able to make invalid cuts and there should be a separate hotkey to undo a cutMatch.
7. - [ ] Should also have a starting state tool that chooses where on the original cutMatch group to start from.
8. - [ ] Starting tool should set all cut matches before the pointer to opacity 100 and the ones after to opacity 40
9. - [ ] Pressing Left/Right Arrow Key should move the hover knotPoint to the next clockwise knotPoint.

## UI Manifold Find Tool

1. - [x] CTRL + VK_F in manifold mode should start a two step process where you click to select two knot points in the manifold.
2. - [x] After the second cut segment is selected search the manifold list for a manifold where the two cut segment's exist and update the manifold index to this manifold.
3. - [ ] If the manifold does not exist ask the user if they'd like to calculate it and add it to the file, otherwise reset without changes.
4. - [ ] Need to change the search to search for closest available to one the user inputted?
5. - [x] Pressing Left/Right Arrow Key should move the hover/selected knotPoint(selected if isn't null) to the next clockwise knotPoint in the list of manifolds.

## UI

1. - [ ] Should have some panel on the right hand side above logo with name of test set, wether the test set is passing, the current tool mode, difference in length between test set answer and calculated answer, etc.
2. - [ ] Make a message pool on the bottom panel
3. - [ ] Display the current tool mode on the right side panel
4. - [x] Make it so Zooming in and out is always centered on the middle of the screen
5. - [x] VK_ESCAPE should return the ui to it's default display state and exit any active tool, resetting the tool.
6. - [ ] VK_O should swap between showing the calculated cutMatch and the one stored in the file and use opposite colors on the color wheel to represent the cutMatch?
7. - [x] VK_B should cycle the manifold index
8. - [ ] Should we only draw one line segment per segment? i.e implement some kind of Z-Buffer? currently just works on the order of drawing, but could imagine storing two colors for every segment and draw each segment as a gradient. As well as storing null color? or a list of segments to draw.
9. - [ ] Need to be able to distinguish between Ctrl + Key and Key with precedence for Ctrl + Key.

## Main Menu

1. - [ ] Have rotating monkeys knot in vector graphics at center.after some time (digits of pi to radians) knot should change rotation direction
2. - [ ] Background should have the same knot scaled up darker and blurred moving in the opposite direction
3. - [ ] central knot should have a pulsing emitting red core and veins across it's surface, use fresnel shader for surface
4. - [ ] Title Should be Ixdar in the same font as the decal.
5. - [ ] Figure out how to load and display 3d models with LWJGL.
6. - [ ] Every frame figure out the outline of the Title and Knot and have a contrail off to the right, the contrail should be produced every frame and should exponentially decay in number of segments.
7. - [ ] Once you go into the tool the knot should stop rotating and a sword should cut it in half.
8. - [ ] Need a Ramp up lerp function in Clock for menu items
9. - [ ] When a menu item is clicked, it should bounce to the left and then go off screen to the right starting with the clicked item and propagating out to the ends of the menu.
10. - [ ] When a menu item is halfway off screen, its partner in the next menu (closest vertically) should come on from the left (ratcheting noise of typewriter?).
11. - [ ] Menu should be scrollable within the menu's bounds.
12. - [ ] Menu items should be elongated hexagons that make an electronic noise when clicked, see ratchet 2.
13. - [ ] Main Menu Item's should include: Continue : last_file_loaded.ix, Load, Puzzle, Map Editor.

## 3D Graphics

1. - [ ] Figure out ASSIMP Model loading
2. - [x] Figure out how to do GLSL Shading
3. - [ ] Figure out how to import shaders from Blender
4. - [ ] Figure out how to blur/depth of field
5. - [ ] Figure out particle systems for sand/dust.

## UI Knot Surface View

1. - [ ] Make a tool that shows the user which of the half segment pairs have low enough external travel cost to check their internal cost, when compared to the lowest simple cut.
2. - [ ] Should display the lowest simple cut when not hovering.
3. - [ ] Should display all the pairs that could be possible as well as the dashed line to it's external value.

## Key Input

1. - [ ] Need to have key bindings menu in settings.
2. - [ ] Should save to a Key bindings file on change and load from the same file on startup

## UI Negative CutMatch View Tool

1. - [x] Make tool to view which KnotPoints have any CutMatches that have a negative weight.
2. - [x] On pressing Ctrl+N should display the top manifold with all half-segments that only have positive weights leading to them in Medium-Green.
3. - [x] Half-Segments that have negative weight cut matches should be colored RED.
4. - [x] When we hover over a RED segment, display all of the cutMatches that lead to that KnotPoint with a negative weight, display the cut Segment in Yellow and the Match Segment in CYAN.
5. - [x] Pressing Left/Right Arrow Key should move the hover knotPoint to the next clockwise knotPoint with negative weight.
6. - [x] Need to make it so you can change the color at the point rather than having only one color per segment.

## Filesystem

1. - [x] When we are in manifold mode update key (VK_U) should update the cut match CUTANS in the respective file.
2. - [x] Should only update when we have the correct answer length and the cutMatch is smaller than the one stored in the file
3. - [x] Manifold files should have the following format djbouti-8-34-manifold_KP1-CP1_KP2-CP2
4. - [x] Should be able to load multiple manifolds per file

## General Speedup

1. - [x]  Remove repeated segment pairs from the main loop (2x speedup)
2. - [ ]  Add worker pool for every dijkstra's call we make (algorithm is somewhat embarrassingly parallel)
3. - [x]  Remove redundant error handling
4. - [ ]  Figure out how to turn into positive weight graph so we can disregard all settled Points.
5. - [ ]  Find some way to calculate all shortest paths for a manifold at once instead of in series (seems unlikely given path dependence)
6. - [x]  For less accuracy dependent problems could use heuristic of distance to KnotPoints from externals plus distance between CutPoints as best measure of where to calculate internal structure changing from N^7 to N^3 operation.
