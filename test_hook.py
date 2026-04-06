#!/usr/bin/env python3
"""Manual test for shader_validation_gate.py hook."""
import subprocess
import sys
import json
import os

os.chdir("/Users/acw28/Code/Ixdar/.worktrees/daud-ix-2")

def run_test(name, input_data, expected_exit=None, expected_contains=None):
    print(f"\n{'='*60}")
    print(f"Test: {name}")
    print(f"{'='*60}")
    # Write input to a temp file and use stdin redirection
    import tempfile
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
        f.write(json.dumps(input_data))
        temp_input = f.name
    
    try:
        result = subprocess.run(
            ["python", ".cursor/hooks/shader_validation_gate.py"],
            stdin=open(temp_input, 'r'),
            capture_output=True,
            text=True
        )
    finally:
        os.unlink(temp_input)
    
    print(f"Exit code: {result.returncode}")
    print(f"Output: {result.stdout}")
    if result.stderr:
        print(f"Stderr: {result.stderr}")
    if expected_exit is not None:
        assert result.returncode == expected_exit, f"Expected exit {expected_exit}, got {result.returncode}"
    if expected_contains:
        for text in expected_contains:
            assert text in result.stdout, f"Expected '{text}' in output"
    print("PASSED")

# Clean up any existing log
import pathlib
log_path = pathlib.Path(".cursor/logs/validation-pending.log")
if log_path.exists():
    log_path.unlink()

# Test 1: afterFileEdit with shader file
run_test(
    "afterFileEdit with .fs shader",
    {"file_path": "test_shader.fs"},
    expected_exit=0,
    expected_contains=["Shader/scene file edited", "validate with a screenshot"]
)

# Test 2: afterFileEdit with .vs shader
run_test(
    "afterFileEdit with .vs shader",
    {"file_path": "test.vs"},
    expected_exit=0,
    expected_contains=["Shader/scene file edited", "validate with a screenshot"]
)

# Test 3: afterFileEdit with .glsl shader
run_test(
    "afterFileEdit with .glsl shader",
    {"file_path": "test.glsl"},
    expected_exit=0,
    expected_contains=["Shader/scene file edited", "validate with a screenshot"]
)

# Test 4: afterFileEdit with non-shader file (should allow silently)
run_test(
    "afterFileEdit with non-shader file",
    {"file_path": "test.txt"},
    expected_exit=0,
    expected_contains=["decision", "allow"]
)

# Test 5: afterFileEdit with scene Java file
run_test(
    "afterFileEdit with scene Java file",
    {"file_path": "scenes/com/example/MyScene.java"},
    expected_exit=0,
    expected_contains=["Shader/scene file edited", "validate with a screenshot"]
)

# Test 6: postToolUse with screenshot command (should clear log)
run_test(
    "postToolUse with screenshot command",
    {"command": "ixdar-cli screenshot"},
    expected_exit=0,
    expected_contains=["Screenshot taken", "cleared"]
)

# Test 7: stop hook with pending files (should deny)
run_test(
    "stop hook with pending files",
    {"hook_type": "stop"},
    expected_exit=2,
    expected_contains=["decision", "deny", "Validation pending", "Before stopping"]
)

# Test 8: stop hook without pending files (should allow)
run_test(
    "stop hook without pending files",
    {"hook_type": "stop"},
    expected_exit=0,
    expected_contains=["decision", "allow"]
)

# Test 9: postToolUse with non-screenshot command (should allow silently)
run_test(
    "postToolUse with non-screenshot command",
    {"command": "echo hello"},
    expected_exit=0,
    expected_contains=["decision", "allow"]
)

print("\n" + "="*60)
print("ALL TESTS PASSED!")
print("="*60)
