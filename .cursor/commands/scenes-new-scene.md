# scenes-new-scene

Create a new runnable scene via the automation CLI scaffolder.

## Preferred Path (CLI)

Run from `tools/ixdar-automation-cli/`:

```bash
python ixdar_cli.py new-scene \
  --name ExampleScene \
  --id example-canvas \
  --subfolder anatomy \
  --display-name "Example Scene" \
  --base Scene \
  --camera 2d \
  --maven-profile example-scene
```

### Parameters

- `--name` (required): PascalCase class name, for example `ExampleScene`
- `--id` (required): kebab-case scene id, for example `example-canvas`
- `--subfolder` (required): subfolder under `ixdar-app/src/main/java/ixdar/scenes/`, for example `anatomy` or `ui/experimental`
- `--display-name` (required): VS Code launch config name
- `--base` (optional): `Scene` or `Canvas3D` (default: `Scene`)
- `--camera` (optional): `2d` or `3d` (default: `2d`)
- `--maven-profile` (optional): kebab-case profile id to add to `ixdar-app/pom.xml`
- `--dry-run` (optional): preview changes without writing files

### Generated Outputs

- Scene class at `ixdar-app/src/main/java/ixdar/scenes/<subfolder>/<Name>.java`
- Launch config in `.vscode/launch.json`
- Optional Maven profile in `ixdar-app/pom.xml` when `--maven-profile` is provided

## Validation

- `mvn -DskipTests compile`
- Launch with VS Code config or `mvn -P <profile-id>`
- Confirm no startup errors and expected scene state

## Notes

- `@SceneAnnotation(id = "...")` is generated automatically, so annotation processing registers the scene in `SceneRegistry_Scenes`.
- For existing scenes, rerun with `--dry-run` first to confirm idempotent behavior.

