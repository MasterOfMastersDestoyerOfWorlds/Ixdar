#!/bin/bash
# docker/teardown.sh - Clean shutdown script for Ixdar agent containers
#
# Usage:
#   ./teardown.sh [agent_index]
#
# This script stops and removes the specified agent container
# and cleans up associated resources.
#
# Example:
#   ./teardown.sh 0  # Stops and removes agent 0

set -e

AGENT_INDEX=${1:-0}
CONTAINER_NAME="ixdar-agent-${AGENT_INDEX}"

echo "=============================================="
echo "Ixdar Container Teardown"
echo "=============================================="
echo "Container Name: ${CONTAINER_NAME}"
echo "=============================================="

# Check if Docker is running
if ! docker info > /dev/null 2>&1; then
    echo "ERROR: Docker is not running."
    exit 1
fi

# Check if container exists
if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "WARNING: Container ${CONTAINER_NAME} does not exist."
    echo "Nothing to clean up."
    exit 0
fi

# Check if container is running
if docker ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "Stopping container ${CONTAINER_NAME}..."
    docker stop ${CONTAINER_NAME}
else
    echo "Container ${CONTAINER_NAME} is not running (stopped or paused)."
fi

# Remove container
echo "Removing container ${CONTAINER_NAME}..."
docker rm ${CONTAINER_NAME}

# Remove image (optional - comment out if you want to keep the image)
echo "Removing image ixdar-agent:${AGENT_INDEX}..."
docker rmi ixdar-agent:${AGENT_INDEX} 2>/dev/null || true

echo ""
echo "=============================================="
echo "✓ Container ${CONTAINER_NAME} has been removed"
echo "=============================================="
echo ""
echo "To start a new agent, run:"
echo "  ./agent-env.sh ${AGENT_INDEX}"
echo ""
echo "Or start a different agent:"
echo "  ./agent-env.sh <new_index>"
echo "=============================================="
