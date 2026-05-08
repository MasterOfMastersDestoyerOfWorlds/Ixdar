package ixdar.procgen.dungeon.player;

import org.joml.Vector3f;

/**
 * Where a {@link PlayerController} should start: world-space center, plus the camera's initial
 * yaw and pitch in degrees. {@code yawDegrees} matches Camera3D's convention (yaw=-90 looks
 * along {@code -Z}).
 */
public record SpawnPoint(Vector3f position, float yawDegrees, float pitchDegrees) { }
