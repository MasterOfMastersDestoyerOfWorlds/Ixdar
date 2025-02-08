# Ixdar Algorithm

## Cutting Algorithm Changes

1. - [ ] New Algorithm to search space within Knot until Knot boundary

2. - [ ] Should only move toward the exit in terms of Knot distance

3. - [ ] Should also have the ability to change the previous Knot boundary's connection

4. - [ ] Need to change cutKnot method to cut all knots on a level at once

5. - [ ] Need to check overlapping cut segments with two knotPoints and one cutPoint, but continue to skip overlapping cut segments with one knotPoint and two cutPoints.

6. - [ ] If we are simply moving through a knot (from one of the choke segments to the opposite one) we should be able to save this route somehow.

7. - [ ] Need to figure out the maximum number of moves within a knot so we can relax the constraints of where we can move and not have as much calculation.

8. - [ ] Need to figure out how to reuse the shortest path info already calculated, f.e. if we move the terminal cut segment counter clockwise we should only invalidate the three effected points and their connections, the rest of the connections should still be valid although we will have to recalculate which of the available cuts are allowed by rules of the hole-moving game.

9. - [x] Figure out how to share shortest path info from a cut to it's flipped version where the ending cutsegment is in the disconnected orientation.

## Knot Finding

1. - [ ] If we have a VirtualPoint A that points to two different internal VirtualPoints B and C in the top layer of a Knot, we need to insert A between B and C.

2. - [ ] Need to Disallow making knots with all internals being knots where the first knot and the last knot do not actually want to match to each other. this is fine when everything is a point, but the correct way to represent this would be : Knot[Knot[Knot[1] Knot[2]] Knot[3]] instead of Knot[Knot[1] Knot[2] Knot[3]]

3. - [ ] Need some rule about joining single points to knots in runs since we are too greedily joining them in 2-knots

4. - [ ] Need to replace "oneOutFlag" in VirtualPoint with a mapping so that we don't trigger over the same point twice. (Do we actually need to remove this?)

5. - [ ] Need to have another method or re-write shouldKnotConsume so that if we see one of our matches instead of quitting we also check to see if that should be consumed, there is a three way check that needs to happen. rather if i am a point and I have am checking if i should be consumed by my neihgbor in the runlist and i run into the oneoutflag break down problems but I break on another knot in the runlist, have to think about what to do there should we form a knot wiht all three? Look at lu634_105-127.ix

## General Speedup

1. - [ ] Add worker pool for every dijkstra's call we make (algorithm is somewhat embarrassingly parallel)

2. - [ ] Figure out how to turn into positive weight graph so we can disregard all settled Points. (Note from future: as long as the knot we are cutting is already a shortest tour subset then we can disregard the settled points, its only when we screw up knot finding or lower level knot cutting that this becomes NP time).

3. - [ ] Find some way to calculate all shortest paths for a manifold at once instead of in series (seems unlikely given path dependence)

# Testing

## Auto Build Unit Testing

1. - [ ] Generate a Unit Test that runs all of the manifold solutions in the folder

2. - [ ] Should compare against the manifold cut match answer in the master file

# UI

## Bugfix

1. - [ ] number labels no longer are aligned with the segment bisector.

2. - [ ] number labels have drawn duplicates see threecircle_in_5

2. - [ ] line segments have jagged edges.

3. - [ ] bug on line culling where the colors flip directions when touching multiple sides of the screen

## Features

1. - [ ] VK_O should swap between showing the calculated cutMatch and the one stored in the file and use opposite colors on the color wheel to represent the cutMatch?

2. - [ ] With fixed size lines there is sometimes a problem of overlap where you can't distinguish between lines once the points get close enough to each other, so we should calculate the minimum width to ensure that this does not happen on startup.

## Tools

### Free Tool

![Complete](readme_img\complete.png)

### Compare Route Map Tool

![Complete](readme_img\complete.png)

### CutMatch Tool

1. - [ ] The original calculated cut match group should be at like opacity 40 or 30.

2. - [ ] Once the user clicks on a highlighted cutMatch pair add that cutMatch to the the edited cutMatch list

3. - [ ] Don't end this state till either the edited cutMatch group finds cutPoint2 or the user presses the hotkey again.

4. - [ ] User should not be able to make invalid cuts and there should be a separate hotkey to undo a cutMatch.

5. - [ ] Should also have a starting state tool that chooses where on the original cutMatch group to start from.

6. - [ ] Starting tool should set all cut matches before the pointer to opacity 100 and the ones after to opacity 40

7. - [ ] Pressing Left/Right Arrow Key should move the hover knotPoint to the next clockwise knotPoint.

8. - [ ] the info panel should show the current length, the length computed length to beat, the list of cuts made so far, the hovered cut and its length delta.

### Find Manifold Tool

1. - [ ] If the manifold does not exist ask the user if they'd like to calculate it and add it to the file, otherwise reset without changes.

2. - [x] Need to change the search to search for closest available to one the user inputted?

3. - [ ] Need to change the manifold finding algo to always find the manifold with the first cut being the source in the routeMap.

### Knot Surface View Tool

1. - [ ] Make a tool that shows the user which of the half segment pairs have low enough external travel cost to check their internal cost, when compared to the lowest simple cut.

2. - [ ] Should display the lowest simple cut when not hovering.

3. - [ ] Should display all the pairs that could be possible as well as the dashed line to it's external value.

### Negative CutMatch View Tool

![Complete](readme_img\complete.png)

## Terminal Panel

### Keyboard Input

1. - [ ] Should be able to hold down a key and see it repeatedly type

### Commands

1. - [x] Every command + key action should also be accessible from the terminal

2. - [x] Should be like a terminal where you can ask questions about the knot you are looking at.

3. - [x] Make Unit Tests Command

4. - [ ] Make a comment on file command

### Tab Completion

1. - [x] Pressing tab on an empty line after running another command should type the next likely command

2. - [ ] Pressing tab on a line with a typed command but missing arguments should cycle through the available options for that command (no cycle on integer inputs).

3. - [ ] Pressing the up arrow should replace the current command line with the previous one in hte history all the way back to the beginning. cache?

### Cursor

1. - [ ] Should have a flashing cursor at the end of the command line

2. - [ ] Pressing right and left arrow should move the cursor's position

3. - [ ] Clicking on the command line should move the cursor to the mouses position.

4. - [ ] Should have a right arrow at the beginning of the command line

### General

1. - [x] Display a scrollable pool of past commands and messages.

2. - [ ] Pressing control and clicking a point, a knot, or a segment should bring its id into the terminal at the cursor

3. - [x] Bezier curve animation behind terminal as fast fluid sim?

4. - [ ] Messages should use hyper text when possible.

5. - [ ] Typing should bring up a tooltip about the the terminal with the commands that have the right letters

## Info Panel

1. - [ ] Should have some panel on the right hand side above logo with name of test set, wether the test set is passing, the current tool mode, difference in length between test set answer and calculated answer, etc.

2. - [ ] Pressing Ctrl+D while over the info box should show a checklist of what info for that tool should be visible, clicking on the x should make that info invisible.

3. - [ ] Default shown info should be saved to a file as defaults on startup

## Tooltip

1. - [ ] Should be rounded rectangle

2. - [x] Should have some constant padding amount

3. - [ ] Should fade text at bottom if too long

4. - [ ] should be light grey with dark grey border

## 2D Camera

![Complete](readme_img\complete.png)

# Menu

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

# Map Editor

1. - [x] Should have a hexagon grid tessellated with equilateral triangles (all grid lengths have length 1)

2. - [ ] Should have a feature that snaps all created or moved points to the grid

3. - [ ] Should be able to drag objects around the map changing the location of all grouped points

4. - [ ] Create a group by selecting a few points and groups of points and pressing ctrl G

5. - [ ] Should be able to bring in Circles, Triangles, other ix files and combine them in one new ix file. Objects should be brought in by either hte command line or by clicking on an add button in the info panel.

6. - [ ] Added objects should be in an group with the points in the object to start.

# Rendering

## Shaders

1. - [ ] Font Atlas from SDF Atlas.

2. - [ ] Line rendering, need to prevent alpha adding on line endpoint intersections when using transparency

3. - [x] Line rendering, need to divide large line boxes into segments so that wew don't get flickering when doing the sdf.

## 3D Graphics

1. - [ ] Figure out ASSIMP Model loading

2. - [ ] Figure out how to blur/depth of field

3. - [ ] Figure out particle systems for sand/dust.

## Shader Editor

1. - [ ] pressing some hotkey (Ctrl, Shift, G) should look at the item that the mouse is currently hovering over and bring up it's glsl shader in vscode with the relevant shader? or should the texture on the right side of the screen and a text editor be on the left internal to the program?

# System I/O

## Key Input

1. - [ ] Need to have key bindings menu in settings.

2. - [ ] Should save to a Key bindings file on change and load from the same file on startup

## Filesystem

1. - [x] Hot reload glsl shaders on change for rapid development

# Triangular Grid

## Hex Points

1. - [x] Write an extension to the PointND for points located on the intersections of the Triangle Grid

2. - [x] Each Point should have three coordinates and distance should be found by taking the max of the differences in the coordinates of two points (this is similar to manhattan distance in the xy plane). see: <https://www.redblobgames.com/grids/hexagons/#distances>

3. - [ ] Lines between hex points can only follow the grid-lines

4. - [x] The grid of possible values should be tiled by equilateral triangles with side length 1

## Show Grid-lines

1. - [x] new Option in the ixdar file type to show grid-lines on startup. If not specified defaulting to the cartesian grid and hidden initially.

2. - [ ] Grid-lines should disappear at multiples of three (so if we zoom out from being able to see a 10 by 10 area to a 100 by 100 area there should be hte same number of grid-lines shown once we have some illegible density)

# City Info

## Display Info

1. - [ ] Every city should have a name, population size, land size in hectares, arable land percent, list of crops, list of exploitable natural resources, list of goods produced

## Natural Resources

1. - [ ] Natural Resources should have an
  - icon
  - quantity in units/hectares/month produces
  - replenish rate in units/hectares/month
  - discovery chance in percent chance to find/hectare
  - discovered resource amount range following a prato distribution
  - cost to explore a hectare
  - percent of explored land for that resource
  - which hectares have been explored
  - consumption rate of the resource by the city
  - price of the resource in the city and all other cities even where you cannot find the resource related to the consumption rate and production cost of the good
  - wether the demand for the resource is elastic or inelastic
  - wether the resource can be exploited on land used for other purposes (e.g. drilling doesn't interfere with farming).

<style>
:root {
  --ixdar: rgb(150, 0, 36); /* set a value "red" to the "color" variable */
}
img[alt=Complete] {
    max-width:300px;
}
h1{
    color: var(--ixdar);
}
</style>
