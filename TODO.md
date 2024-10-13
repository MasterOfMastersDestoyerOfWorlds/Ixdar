# TODO

## Cutting Algorithm Changes

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

1. - [ ] Generate a Unit Test that runs all of the manifold solutions in the folder
2. - [ ] Should compare against the manifold cut match answer in the master file

## UI Tools General

1. - [ ] Tools should specify what type of bounding boxes that they should use? (Segment rectangle versus radius from point?)
2. - [ ] Each Tool should tell the user some information about what to do when using it in the message pool.

## UI Free Tool

1. - [ ] Pressing VK_K should switch to displaying the same information about the current knot the point is in.
2. - [ ] Knot's should display their closest two segments with rotating dashed lines. (use dash phase).
3. - [ ] Info Panel should display the point's containing Knot was well as the full knot structure with different colors for the different knots being represented by their metro diagram colors
4. - [ ] hovering over the knot text should switch the metro draw index to display that knot and highlight it somehow
5. - [ ] point's info should include generated or lookup city name.

## UI CutMatch Tool

1. - [ ] The original calculated cut match group should be at like opacity 40 or 30.
2. - [ ] Once the user clicks on a highlighted cutMatch pair add that cutMatch to the the edited cutMatch list
3. - [ ] Don't end this state till either the edited cutMatch group finds cutPoint2 or the user presses the hotkey again.
4. - [ ] User should not be able to make invalid cuts and there should be a separate hotkey to undo a cutMatch.
5. - [ ] Should also have a starting state tool that chooses where on the original cutMatch group to start from.
6. - [ ] Starting tool should set all cut matches before the pointer to opacity 100 and the ones after to opacity 40
7. - [ ] Pressing Left/Right Arrow Key should move the hover knotPoint to the next clockwise knotPoint.
8.   - [ ] the info panel should show the current length, the length computed length to beat, the list of cuts made so far, the hovered cut and its length delta.

## UI Manifold Find Tool

1. - [ ] If the manifold does not exist ask the user if they'd like to calculate it and add it to the file, otherwise reset without changes.
2. - [ ] Need to change the search to search for closest available to one the user inputted?

## UI Message Panel


## UI Info Panel

1. - [ ] Should have some panel on the right hand side above logo with name of test set, wether the test set is passing, the current tool mode, difference in length between test set answer and calculated answer, etc.
2. - [ ] Panel should be scrollable
3. - [ ] Pressing Ctrl+D should show a box under your mouse showing what info you want shown in the info panel for that tool
4. - [ ] Default shown info should be saved to a file as defaults on startup

## UI

1. - [ ] Make a message pool on the bottom panel
2. - [ ] VK_O should swap between showing the calculated cutMatch and the one stored in the file and use opposite colors on the color wheel to represent the cutMatch?

## Main Menu

1. - [ ] Have rotating monkeys knot in vector graphics at center.after some time (digits of pi to radians) knot should change rotation direction
2. - [ ] Background should have the same knot scaled up darker and blurred moving in the opposite direction
3. - [ ] central knot should have a pulsing emitting red core and veins across it's surface, use fresnel shader for surface
4. - [ ] Title Should be Ixdar in the same font as the decal.
5. - [ ] Figure out how to load and display 3d models with LWJGL.
6. - [ ] Every frame figure out the outline of the Title and Knot and have a contrail off to the right, the contrail should be produced every frame and should exponentially decay in number of segments.
7. - [ ] Once you go into the tool the knot should stop rotating and a sword should cut it in half.

## Menu Items

1. - [ ] Need a Ramp up lerp function in Clock for menu items
2. - [ ] When a menu item is clicked, it should bounce to the left and then go off screen to the right starting with the clicked item and propagating out to the ends of the menu.
3. - [ ] When a menu item is halfway off screen, its partner in the next menu (closest vertically) should come on from the left (ratcheting noise of typewriter?).
4. - [ ] Menu Items should make an electronic noise click noise when hovered over
5. - [ ] Menu Items should use a custom SDF Font Atlas

## Shaders

1. - [ ] Font Atlas from SDF Atlas.

## 3D Graphics

1. - [ ] Figure out ASSIMP Model loading
2. - [ ] Figure out how to blur/depth of field
3. - [ ] Figure out particle systems for sand/dust.

## UI Knot Surface View Tool

1. - [ ] Make a tool that shows the user which of the half segment pairs have low enough external travel cost to check their internal cost, when compared to the lowest simple cut.
2. - [ ] Should display the lowest simple cut when not hovering.
3. - [ ] Should display all the pairs that could be possible as well as the dashed line to it's external value.

## Key Input

1. - [ ] Need to have key bindings menu in settings.
2. - [ ] Should save to a Key bindings file on change and load from the same file on startup

## UI Negative CutMatch View Tool

1. - [ ] Info Panel shouuld show the current hover cut's length delta as well as name the segments involved

## Filesystem

1. - [ ] Hot reload glsl shaders on change for rapid development

## General Speedup

1. - [ ]  Add worker pool for every dijkstra's call we make (algorithm is somewhat embarrassingly parallel)
2. - [ ]  Figure out how to turn into positive weight graph so we can disregard all settled Points.
3. - [ ]  Find some way to calculate all shortest paths for a manifold at once instead of in series (seems unlikely given path dependence)

## Bugfix

1. - [ ] investigate why there is a second to load graphics before the window becomes the right size.