#!/bin/bash
# docker/agent-env.sh - Helper script for starting an Ixdar agent development container
#
# Usage:
#   ./agent-env.sh [agent_index]
#
# This script:
# 1. Starts a Docker container for the specified agent
# 2. Runs Maven compilation
# 3. Launches the mesh viewer scene with headless OpenGL
# 4. Outputs the base URL for automation API access
#
# Example:
#   ./agent-env.sh 0  # Starts agent 0 on port 47832
#   ./agent-env.sh 1  # Starts agent 1 on port 47833

set -e

# Configuration
AGENT_INDEX=${1:-0}
BASE_PORT=47832
CONTAINER_NAME="ixdar-agent-${AGENT_INDEX}"
AUTOMATION_PORT=$((BASE_PORT + AGENT_INDEX))
HOST_PORT=$AUTOMATION_PORT

# Display configuration for Xvfb
XVFB_WIDTH=1920
XVFB_HEIGHT=1080
XVFB_DEPTH=24

echo "=============================================="
echo "Ixdar Agent Development Environment"
echo "=============================================="
echo "Agent Index: ${AGENT_INDEX}"
echo "Container Name: ${CONTAINER_NAME}"
echo "Automation Port: ${AUTOMATION_PORT}"
echo "Base URL: http://localhost:${AUTOMATION_PORT}"
echo "=============================================="

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "ERROR: Docker is not running. Please start Docker Desktop or Docker daemon."
    exit 1
fi

# Check if container already exists
if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "WARNING: Container ${CONTAINER_NAME} already exists."
    echo "Use ./teardown.sh ${AGENT_INDEX} to remove it first."
    exit 1
fi

# Check if port is in use
if lsof -i:${HOST_PORT} > /dev/null 2>&1; then
    echo "ERROR: Port ${HOST_PORT} is already in use."
    echo "Use a different agent index or stop the conflicting process."
    exit 1
fi

echo ""
echo "Building Docker image..."
cd "$(dirname "$0")/.."
docker build -t ixdar-agent:${AGENT_INDEX} -f docker/Dockerfile .

echo ""
echo "Starting container ${CONTAINER_NAME}..."
docker run -d \
    --name "${CONTAINER_NAME}" \
    -p "${HOST_PORT}:47832" \
    -e IXDAR_AUTOMATION_PORT="${AUTOMATION_PORT}" \
    -e DISPLAY=:99 \
    -e Xvfb_WIDTH="${XVFB_WIDTH}" \
    -e Xvfb_HEIGHT="${XVFB_HEIGHT}" \
    -e Xvfb_DEPTH="${XVFB_DEPTH}" \
    -e JAVA_OPTS="-Djava.awt.headless=true -Dorg.lwjgl.util.DebugLoader=false" \
    -v "$(pwd)/ixdar-app/src:/ixdar/ixdar-app/src" \
    -v "$(pwd)/annotations:/ixdar/annotations" \
    -v "$(pwd)/ixdar_automation_cli:/ixdar/ixdar_automation_cli" \
    -v "$(pwd)/ixdar-renders:/ixdar/ixdar-renders" \
    -w /ixdar \
    ixdar-agent:${AGENT_INDEX} \
    /bin/bash -c "
        /etc/init.d/xvfb start &&
        sleep 2 &&
        echo 'Xvfb started on :99' &&
        mvn clean package -P mesh-viewer -DskipTests &&
        echo 'Build complete' &&
        java \$JAVA_OPTS \
          -Dixdar.automation.port=\${IXDAR_AUTOMATION_PORT} \
          -cp ixdar-app/target/ixdar-app-0.0.1-jar-with-dependencies.jar \
          ixdar.canvas.IxdarWindow mesh-viewer &
        sleep 5 &&
        echo 'Ixdar mesh viewer started' &&
        echo 'Automation API available at http://localhost:\${IXDAR_AUTOMATION_PORT}' &&
        wait
    "

echo ""
echo "Waiting for container to be ready..."
sleep 10

# Verify the container is running
if ! docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "ERROR: Container failed to start. Check logs with: docker logs ${CONTAINER_NAME}"
    exit 1
fi

echo ""
echo "=============================================="
echo "✓ Container ${CONTAINER_NAME} is running"
echo "=============================================="
echo ""
echo "Automation API Base URL: http://localhost:${AUTOMATION_PORT}"
echo ""
echo "Use the following commands:"
echo "  - View logs:          docker logs ${CONTAINER_NAME}"
echo "  - Stop container:     ./teardown.sh ${AGENT_INDEX}"
echo "  - Open shell:         docker exec -it ${CONTAINER_NAME} /bin/bash"
echo "  - Run CLI command:    docker exec ${CONTAINER_NAME} python3 -m ixdar_automation_cli --base-url http://localhost:${AUTOMATION_PORT} <command>"
echo ""
echo "Container is ready for agent development."
echo "=============================================="
