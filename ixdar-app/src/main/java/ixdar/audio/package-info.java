/**
 * OpenAL playback for desktop: `AudioSystem` singleton with WAV caching, music loop, SFX, and
 * volume control. Reachable only by reflection from `Canvas3D`; no compile-time dependency from
 * the render path, which keeps OpenAL out of the web build. Observability accessors exist for
 * automation assertions.
 */
package ixdar.audio;
