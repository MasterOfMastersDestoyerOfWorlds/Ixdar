# Ixdar Dockerized Development Environment

This directory contains a Docker-based development environment that allows multiple AI agents to develop mesh nodes in parallel, each in their own isolated container.

## Features

- **Isolated Containers**: Each agent runs in its own container with no shared state
- **Unique Ports**: Each container gets a unique automation API port (47832 + agent_index)
- **Headless OpenGL**: Software rendering via Xvfb + Mesa llvmpipe for CI/CD compatibility
- **Parallel Development**: N agents can work on N different node tickets simultaneously
- **Clean Teardown**: Simple scripts to stop and remove containers

## Quick Start

### 1. Start an Agent Container

```bash
cd docker
./agent-env.sh 0
```

This starts agent 0 on port 47832. The script will:
- Build the Docker image
- Start the container with Xvfb for headless display
- Run Maven compilation
- Launch the mesh viewer scene
- Output the base URL for automation API access

### 2. Use the Automation CLI

Once the container is running, you can use the automation CLI:

```bash
# View the current UI state
python3 -m ixdar_automation_cli --base-url http://localhost:47832 ui-state

# Take a screenshot
python3 -m ixdar_automation_cli --base-url http://localhost:47832 screenshot out.png
```

### 3. Stop the Container

```bash
./teardown.sh 0
```

## Multi-Agent Setup

To start multiple agents in parallel:

```bash
# Start 3 agents
./agent-env.sh 0
./agent-env.sh 1
./agent-env.sh 2

# Each agent runs on a unique port:
# Agent 0: http://localhost:47832
# Agent 1: http://localhost:47833
# Agent 2: http://localhost:47834
```

Or use docker-compose to scale automatically:

```bash
# Start 3 agents using docker-compose
docker-compose up --scale ixdar-agent=3
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `AGENT_INDEX` | 0 | Unique identifier for this agent |
| `IXDAR_AUTOMATION_PORT` | 47832 + AGENT_INDEX | Automation API port |
| `XVFB_WIDTH` | 1920 | Xvfb display width |
| `XVFB_HEIGHT` | 1080 | Xvfb display height |
| `XVFB_DEPTH` | 24 | Xvfb color depth |
| `JAVA_OPTS` | -Djava.awt.headless=true | Java options for headless mode |

## Container Specifications

- **Base Image**: openjdk:21-jdk-slim
- **Java**: 21 (LTS)
- **Maven**: 3.9.6
- **LWJGL**: 3.3.6
- **OpenGL**: Mesa llvmpipe (software rendering)
- **Display**: Xvfb (virtual framebuffer)

## Directory Structure

```
docker/
├── Dockerfile           # Container build definition
├── docker-compose.yml   # Docker Compose template for scaling
├── agent-env.sh         # Helper script to start an agent
├── teardown.sh          # Helper script to stop/remove an agent
└── README.md            # This file
```

## Automation API Reference

The automation API is exposed on port 47832 + agent_index:

- `/health` - Health check endpoint
- `/ui/state` - Current UI state
- `/ui/screenshot` - Take a screenshot
- `/ui/mesh/fingerprint` - Get mesh SHA-256 fingerprint
- `/input/click` - Simulate mouse click
- `/input/hover` - Simulate mouse hover
- `/input/key` - Simulate keyboard input
- `/shutdown` - Shutdown the application

## Development Workflow

1. **Start your agent container**: `./agent-env.sh <index>`
2. **Develop your mesh node**: Work on the node code in the mounted source directory
3. **Test with CLI**: Use the automation CLI to test your changes
4. **Validate DSL files**: Use `validate_dsl` to validate Ixdar DSL files
5. **Take screenshots**: Use the automation CLI to capture renders
6. **Stop when done**: `./teardown.sh <index>`

## Troubleshooting

### Container fails to start

Check the logs:
```bash
docker logs ixdar-agent-<index>
```

### Port already in use

Use a different agent index:
```bash
./agent-env.sh 1  # Try agent 1 instead
```

### Build fails

The Maven build runs automatically when the container starts. Check the container logs for details.

### Screenshots don't render

Ensure Xvfb is running and the display is configured correctly. The agent-env.sh script handles this automatically.

## License

Same as the parent Ixdar project.
