/**
 * `DungeonViewerScene`, a model scene over the dungeon graphs: F toggles fly-cam vs player-walk, V
 * toggles person view; only 3D dungeons support player mode since the graph's mesh feeds both
 * renderer and collision. Its keys are control rows; its camera controllers are a `FlyController`
 * and the `ThirdPersonCamera`.
 */
package ixdar.scenes.dungeon;
