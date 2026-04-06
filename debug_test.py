#!/usr/bin/env python3
"""Debug test for shader_validation_gate.py hook."""
import json
import sys

# Simulate the hook logic
payload = {"hook_type": "afterFileEdit", "file_path": "test_shader.fs"}

print(f"Payload: {payload}")

# Extract file path
file_path = payload.get("file_path")
print(f"Extracted file_path: {file_path}")

# Check if it's a shader file
from pathlib import Path
SHADER_EXTENSIONS = {".fs", ".vs", ".glsl"}
is_shader = Path(file_path).suffix.lower() in SHADER_EXTENSIONS
print(f"Is shader file: {is_shader}")
print(f"Suffix: {Path(file_path).suffix}")

# Check scene Java
is_scene = "scenes" in Path(file_path).parts
print(f"Is scene Java: {is_scene}")

# Combined check
should_validate = is_shader or is_scene
print(f"Should validate: {should_validate}")
