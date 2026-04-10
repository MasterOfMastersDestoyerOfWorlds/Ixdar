# Headless Mesh Rendering Testing Plan

## Overview

This document describes the testing approach for the headless mesh rendering CLI (`RenderDsl`).

## Test Cases

### 1. Basic Cube Render

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl test_cube.png
```

**Expected Output:**
- PNG file created at `test_cube.png`
- Console output showing mesh stats (8 verts, 6 faces)
- Exit code 0

**Validation:**
- File exists and is non-zero size
- Image renders a cube mesh

### 2. Curve Sweep Tube Render

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/curve_sweep_tube.dsl test_tube.png
```

**Expected Output:**
- PNG file created at `test_tube.png`
- Console output showing mesh stats
- Exit code 0

**Validation:**
- File exists and is non-zero size
- Image renders tube geometry

### 3. Custom Resolution

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl test_1024.png --width 1024 --height 1024
```

**Expected Output:**
- PNG file created at `test_1024.png`
- Image resolution is 1024x1024 pixels

### 4. Error Handling - Missing DSL File

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl nonexistent.dsl output.png 2>&1
```

**Expected Output:**
- Error message to stderr
- Non-zero exit code

### 5. Error Handling - Missing Arguments

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl 2>&1
```

**Expected Output:**
- Usage message to stderr
- Non-zero exit code

### 6. Error Handling - Invalid Option

**Command:**
```bash
java -XstartOnFirstThread -cp target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl output.png --invalid 2>&1
```

**Expected Output:**
- Error message to stderr
- Non-zero exit code

## CI/CD Integration

### macOS CI

No additional setup required. Use `-XstartOnFirstThread` flag.

```yaml
- name: Render DSL
  run: |
    mvn clean package -pl ixdar-app -am -DskipTests
    java -XstartOnFirstThread -cp ixdar-app/target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl cube.png
```

### Linux CI (with Xvfb)

```yaml
- name: Setup Xvfb
  run: |
    sudo apt-get update
    sudo apt-get install -y xvfb
    Xvfb :99 -screen 0 1920x1080x24 &
    export DISPLAY=:99

- name: Render DSL
  run: |
    mvn clean package -pl ixdar-app -am -DskipTests
    java -XstartOnFirstThread -cp ixdar-app/target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl cube.png
```

### Linux CI (with Mesa Software Rendering)

```yaml
- name: Render DSL
  run: |
    export LIBGL_ALWAYS_SOFTWARE=1
    mvn clean package -pl ixdar-app -am -DskipTests
    java -XstartOnFirstThread -cp ixdar-app/target/ixdar-app-0.0.1-jar-with-dependencies.jar ixdar.cli.RenderDsl src/main/resources/dsl/cube.dsl cube.png
```

### Docker with Mesa

```dockerfile
FROM ubuntu:22.04

RUN apt-get update && apt-get install -y \
    openjdk-21-jdk \
    libglfw3 \
    libgl1-mesa-glx \
    libgl1-mesa-dri \
    xvfb

WORKDIR /app

COPY ixdar-app/target/ixdar-app-0.0.1-jar-with-dependencies.jar .
COPY ixdar-app/src/main/resources/dsl ./dsl

CMD ["java", "-XstartOnFirstThread", "-cp", "ixdar-app-0.0.1-jar-with-dependencies.jar", \
     "ixdar.cli.RenderDsl", "dsl/cube.dsl", "cube.png"]
```

## System Requirements

### macOS
- Java 21+
- `-XstartOnFirstThread` flag required

### Linux
- Java 21+
- OpenGL-capable display server (Xvfb, X11, or Mesa software rendering)
- LWJGL GLFW library

### Dependencies
- LWJGL 3.3.6
- JOML 1.10.8
- Gson 2.10.1
- Apache Commons Math 3.1
- Apache Commons Collections 4.5.0-M1
- Apache Commons Lang 3.14.0

## Verification Checklist

- [x] CLI main class (`ixdar.cli.RenderDsl`) exists
- [x] `render-dsl` Maven profile configured in pom.xml
- [x] Sample DSL files bundled (`cube.dsl`, `curve_sweep_tube.dsl`)
- [x] HeadlessGL initialization with invisible window
- [x] PNG output via `HeadlessPlatform.screenshot()`
- [x] Proper error handling with non-zero exit codes
- [x] Documentation in README.md
- [x] Testing plan documented
