# Headless Mesh Rendering

## Overview

The `RenderDsl` CLI enables offscreen mesh rendering to PNG without requiring a desktop environment or the Ixdar automation server. This is useful for:

- CI/CD pipeline mesh validation screenshots
- Daud/ticket-worker automated rendering
- Deterministic mesh visualization without GUI dependencies

## Usage

```bash
java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl <input.dsl> <output.png> [options]
```

### Options

| Flag | Description | Default |
|------|-------------|---------|
| `--node <id>` | Final node ID to render (default: last node in graph) | last node |
| `--port <name>` | Output port name (default: auto-detect "mesh" or "geometry") | auto-detect |
| `--width <N>` | Image width in pixels | 512 |
| `--height <N>` | Image height in pixels | 512 |

### Examples

```bash
# Render a simple cube
java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl cube.png

# Render with custom resolution
java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl cube_1024.png --width 1024 --height 1024

# Render curve sweep tube
java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl src/main/resources/dsl/curve_sweep_tube.dsl tube.png
```

## System Requirements

### macOS

- Works natively with `-XstartOnFirstThread` flag
- No additional dependencies required

### Linux (CI/Headless)

Requires an OpenGL-capable display server. Options:

1. **Xvfb (X Virtual Framebuffer)** - Recommended for CI
   ```bash
   sudo apt-get install xvfb
   Xvfb :99 -screen 0 1920x1080x24 &
   export DISPLAY=:99
   java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl ...
   ```

2. **Mesa software rendering**
   ```bash
   export LIBGL_ALWAYS_SOFTWARE=1
   java -XstartOnFirstThread -classpath target/ixdar-app-0.0.1.jar ixdar.cli.RenderDsl ...
   ```

3. **Docker with Mesa**
   ```dockerfile
   FROM ubuntu:22.04
   RUN apt-get update && apt-get install -y \
       openjdk-21-jdk \
       libglfw3 \
       libgl1-mesa-glx \
       libgl1-mesa-dri \
       xvfb
   ```

## Implementation Details

- Uses LWJGL GLFW to create an invisible window for real OpenGL 3.3 context
- `HeadlessPlatform` implements the `Platform` interface for offscreen rendering
- `HeadlessGL` handles GL resource management and pixel reading
- Mesh rendering reuses the existing `HalfEdgeMeshRuntime` shader pipeline
- PNG output via `javax.imageio.ImageIO` with proper Y-flip for OpenGL coordinate system

## Error Handling

- Non-zero exit code on DSL parsing errors
- Non-zero exit code on mesh generation failures
- Non-zero exit code on GL initialization failures
- Detailed error messages printed to stderr

## Related

- `ixdar.platform.gl.headless.HeadlessGL`
- `ixdar.platform.gl.headless.HeadlessPlatform`
- `ixdar.cli.RenderDsl`
