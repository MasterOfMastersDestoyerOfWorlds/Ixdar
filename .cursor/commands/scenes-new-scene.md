# scenes-new-scene

Create a new runnable scene under `ixdar-app/src/main/java/ixdar/scenes/` .

## Steps

* Create a scene class in the appropriate domain package (for example `ixdar.scenes.trade` or `ixdar.scenes.anatomy`).
* Add `@SceneAnnotation(id = "your-scene-id")` so annotation processing registers it in `CanvasSceneMap`.
* Choose a base class:
  + Extend `Scene` for shader/demo scenes that use `initCodePane` and custom draw flow.
  + Extend `Canvas3D` for gameplay scene bootstraps that trigger existing flows.
* Implement lifecycle hooks:
  + `initPoints()` when custom points/shell data is needed.
  + `initGL()` for startup setup and one-time initialization.
  + `drawScene()` for per-frame rendering behavior.
* Add a VS Code launch config in `.vscode/launch.json`:
  + `mainClass`: `ixdar.canvas.IxdarWindow`
  + `args`: your scene id (for example `irregular-grid-canvas`)
* Add a Maven run profile in `ixdar-app/pom.xml` when the scene needs a named CLI launcher:
  + Profile should run `compile exec:exec` and pass the scene id argument to `IxdarWindow`.
* Validate:
  + `mvn -DskipTests compile`
  + Launch with VS Code config or `mvn -P <profile-id>`
  + Confirm no startup errors and expected scene state.

## Minimal Template

```java
package ixdar.scenes.example;

import ixdar.annotations.scene.SceneAnnotation;
import ixdar.scenes.Scene;

@SceneAnnotation(id = "example-canvas")
public class ExampleScene extends Scene {
    @Override
    public void initGL() {
        super.initGL();
    }

    @Override
    public void drawScene() {
        super.drawScene();
    }
}
```
