/**
 * OpenAL playback for desktop: `AudioSystem` singleton with WAV caching, music loop, SFX, and
 * volume control. Reached only through `Platform.audio()`, which the browser platform answers with
 * null, so OpenAL stays out of the web build. Observability accessors exist for automation assertions.
 */
package ixdar.audio;
