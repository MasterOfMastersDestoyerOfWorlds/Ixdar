/**
 * Bootstrap layer and root drawable. `IxdarWindow` (desktop main: GL on a dedicated render thread,
 * GLFW polling on the main thread, headless mode via `ixdar.headless`), `WebLauncher` (TeaVM main),
 * `Canvas3D` (base of every scene), `CanvasSceneMap` (registry plus two hand-registered ids).
 */
package ixdar.canvas;
