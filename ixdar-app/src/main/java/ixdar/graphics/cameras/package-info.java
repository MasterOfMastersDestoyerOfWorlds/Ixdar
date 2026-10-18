/**
 * `Camera2D` (pan/scale, zoom-to-fit) and `Camera3D` (first-person, or an orbit whose state it
 * holds) behind a 2D-shaped `Camera` interface; several `Camera3D` methods throw. `CameraController`
 * is how a camera answers drags, the wheel and held keys: `Camera2D` pans, `Camera3D` orbits,
 * `FlyController` flies. `Bounds` is the viewport rectangle with a resize recalculator.
 */
package ixdar.graphics.cameras;
