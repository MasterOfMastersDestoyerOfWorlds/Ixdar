package ixdar.audio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.AL10;

import ixdar.platform.Platforms;

public class AudioSystem {
    public static final String LOAD_FAIL = "LOAD_FAIL ";
    public static final String RES_AUDIO_MUSIC = "res/audio/music/";
    public static final String RES_AUDIO_SFX = "res/audio/sfx/";
    public static final String IXDARASSETS = "IxdarAssets";
    public static final String AUDIO = "[Audio] ";
    public static final int NUM_16 = 16;
    public static final int NUM_4096 = 4096;
    public static final float NUM_0 = 0f;
    public static final float NUM_1 = 1f;
    public static final int NUM_200 = 200;
    private static final AudioSystem INSTANCE = new AudioSystem();

    private long device;
    private long context;
    private boolean initialized;
    private boolean available;

    private int menuMusicSource = -1;
    private String currentMenuMusicPath;

    private float masterVolume = 1f;
    private float musicVolume = 1f;
    private float sfxVolume = 1f;

    private String lastSfxPlayed = "";
    private final HashMap<String, Integer> sfxPlayCount = new HashMap<>();
    private final HashMap<String, Integer> audioBuffers = new HashMap<>();
    private final ArrayList<Integer> transientSources = new ArrayList<>();
    private final ArrayList<String> eventLog = new ArrayList<>();
    private long eventCounter = 0L;

    /**
     * Process-wide singleton accessor.
     *
     * @return shared {@link AudioSystem} instance
     */
    public static AudioSystem get() {
        return INSTANCE;
    }

    /**
     * Open the OpenAL device and context, allocate the menu-music source, and mark
     * the system available; idempotent and never throws (failures disable audio).
     */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) {
                log("Audio disabled: unable to open OpenAL device.");
                return;
            }
            context = ALC10.alcCreateContext(device, (int[]) null);
            if (context == 0L) {
                log("Audio disabled: unable to create OpenAL context.");
                ALC10.alcCloseDevice(device);
                device = 0L;
                return;
            }
            ALC10.alcMakeContextCurrent(context);
            ALCCapabilities alcCapabilities = ALC.createCapabilities(device);
            ALCapabilities alCapabilities = AL.createCapabilities(alcCapabilities);
            if (!alCapabilities.OpenAL10) {
                log("Audio disabled: OpenAL 1.0 not available.");
                ALC10.alcDestroyContext(context);
                ALC10.alcCloseDevice(device);
                context = 0L;
                device = 0L;
                return;
            }
            menuMusicSource = AL10.alGenSources();
            available = true;
            log("Audio initialized.");
            addAudioEvent("INIT_OK");
        } catch (Throwable t) {
            available = false;
            log("Audio disabled: " + t.getMessage());
            addAudioEvent("INIT_FAIL " + t.getMessage());
            cleanupOpenAL();
        }
    }

    /**
     * Whether OpenAL initialized successfully and audio playback is usable.
     *
     * @return {@code true} if init succeeded and the device/context are live
     */
    public synchronized boolean isAvailable() {
        return available;
    }

    /**
     * Start (or resume) looping music on the dedicated menu-music source. Loads the
     * WAV if needed, swaps buffers when the path changes, and is a no-op if already
     * playing the same track.
     *
     * @param relativeAssetPath classpath/asset path to a WAV file
     */
    public synchronized void playMenuMusicLoop(String relativeAssetPath) {
        if (!ensureReady()) {
            return;
        }
        cleanupFinishedTransientSources();
        int buffer = loadWavBuffer(relativeAssetPath);
        if (buffer < 0) {
            return;
        }
        if (!relativeAssetPath.equals(currentMenuMusicPath)) {
            AL10.alSourceStop(menuMusicSource);
            AL10.alSourcei(menuMusicSource, AL10.AL_BUFFER, buffer);
            currentMenuMusicPath = relativeAssetPath;
        }
        AL10.alSourcef(menuMusicSource, AL10.AL_GAIN, musicVolume * masterVolume);
        AL10.alSourcei(menuMusicSource, AL10.AL_LOOPING, AL10.AL_TRUE);
        int state = AL10.alGetSourcei(menuMusicSource, AL10.AL_SOURCE_STATE);
        if (state != AL10.AL_PLAYING) {
            AL10.alSourcePlay(menuMusicSource);
            addAudioEvent("MUSIC_PLAY " + relativeAssetPath);
        }
    }

    /**
     * Pause the menu-music source if it is currently playing; otherwise no-op.
     */
    public synchronized void pauseMenuMusic() {
        if (!available || menuMusicSource < 0) {
            return;
        }
        int state = AL10.alGetSourcei(menuMusicSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING) {
            AL10.alSourcePause(menuMusicSource);
            addAudioEvent("MUSIC_PAUSE");
        }
    }

    /**
     * Stop the menu-music source unconditionally (rewinds to start).
     */
    public synchronized void stopMenuMusic() {
        if (!available || menuMusicSource < 0) {
            return;
        }
        AL10.alSourceStop(menuMusicSource);
        addAudioEvent("MUSIC_STOP");
    }

    /**
     * Play a one-shot sound effect on a fresh transient source; the source is
     * tracked and freed once playback finishes.
     *
     * @param relativeAssetPath classpath/asset path to a WAV file
     */
    public synchronized void playSfxOnce(String relativeAssetPath) {
        if (!ensureReady()) {
            return;
        }
        cleanupFinishedTransientSources();
        int buffer = loadWavBuffer(relativeAssetPath);
        if (buffer < 0) {
            return;
        }
        int source = AL10.alGenSources();
        AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        AL10.alSourcef(source, AL10.AL_GAIN, sfxVolume * masterVolume);
        AL10.alSourcePlay(source);
        transientSources.add(source);
        lastSfxPlayed = relativeAssetPath;
        sfxPlayCount.put(relativeAssetPath, sfxPlayCount.getOrDefault(relativeAssetPath, 0) + 1);
        addAudioEvent("SFX_PLAY " + relativeAssetPath);
    }

    /**
     * Whether the menu-music source is currently in the playing state.
     *
     * @return {@code true} only when state is {@code AL_PLAYING}
     */
    public synchronized boolean isMenuMusicPlaying() {
        if (!available || menuMusicSource < 0) {
            return false;
        }
        return AL10.alGetSourcei(menuMusicSource, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
    }

    /**
     * Active menu-music source count for diagnostics: 1 if playing or paused, else 0.
     *
     * @return 0 or 1
     */
    public synchronized int getMenuMusicSourceCount() {
        if (!available || menuMusicSource < 0) {
            return 0;
        }
        int state = AL10.alGetSourcei(menuMusicSource, AL10.AL_SOURCE_STATE);
        if (state == AL10.AL_PLAYING || state == AL10.AL_PAUSED) {
            return 1;
        }
        return 0;
    }

    /**
     * Asset path of the most recent {@link #playSfxOnce} call (empty string if none).
     *
     * @return last SFX path
     */
    public synchronized String getLastSfxPlayed() {
        return lastSfxPlayed;
    }

    /**
     * Defensive copy of the per-asset SFX play counters.
     *
     * @return new map mapping asset path to play count
     */
    public synchronized Map<String, Integer> getSfxPlayCountSnapshot() {
        return new HashMap<>(sfxPlayCount);
    }

    /**
     * Defensive copy of the rolling audio-event log (most recent 200 entries).
     *
     * @return new list of {@code "counter|epochMs|event"} lines
     */
    public synchronized ArrayList<String> getEventLogSnapshot() {
        return new ArrayList<>(eventLog);
    }

    /**
     * Set the master gain multiplier and refresh live source gains.
     *
     * @param volume desired gain, clamped to {@code [0, 1]}
     */
    public synchronized void setMasterVolume(float volume) {
        masterVolume = clamp(volume);
        refreshSourceGains();
    }

    /**
     * Set the music-channel gain multiplier and refresh live source gains.
     *
     * @param volume desired gain, clamped to {@code [0, 1]}
     */
    public synchronized void setMusicVolume(float volume) {
        musicVolume = clamp(volume);
        refreshSourceGains();
    }

    /**
     * Set the SFX-channel gain multiplier and refresh live source gains.
     *
     * @param volume desired gain, clamped to {@code [0, 1]}
     */
    public synchronized void setSfxVolume(float volume) {
        sfxVolume = clamp(volume);
        refreshSourceGains();
    }

    /**
     * Release every OpenAL source and buffer, destroy the context, and close the
     * device; safe to call repeatedly.
     */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        cleanupFinishedTransientSources();
        if (available) {
            for (int source : transientSources) {
                AL10.alDeleteSources(source);
            }
            transientSources.clear();
            if (menuMusicSource >= 0) {
                AL10.alDeleteSources(menuMusicSource);
                menuMusicSource = -1;
            }
            for (int buffer : audioBuffers.values()) {
                AL10.alDeleteBuffers(buffer);
            }
            audioBuffers.clear();
        }
        cleanupOpenAL();
        initialized = false;
        available = false;
    }

    private boolean ensureReady() {
        if (!initialized) {
            init();
        }
        return available;
    }

    private void refreshSourceGains() {
        if (!available) {
            return;
        }
        if (menuMusicSource >= 0) {
            AL10.alSourcef(menuMusicSource, AL10.AL_GAIN, musicVolume * masterVolume);
        }
        for (int source : transientSources) {
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (state == AL10.AL_PLAYING) {
                AL10.alSourcef(source, AL10.AL_GAIN, sfxVolume * masterVolume);
            }
        }
    }

    private int loadWavBuffer(String classpathResourcePath) {
        if (audioBuffers.containsKey(classpathResourcePath)) {
            return audioBuffers.get(classpathResourcePath);
        }
        try {
            InputStream resourceStream = AudioSystem.class.getClassLoader().getResourceAsStream(classpathResourcePath);
            if (resourceStream == null) {
                File file = resolveAudioFile(classpathResourcePath);
                if (file != null && file.exists()) {
                    resourceStream = Files.newInputStream(file.toPath());
                }
            }
            if (resourceStream == null) {
                log("Missing audio resource: " + classpathResourcePath);
                addAudioEvent(LOAD_FAIL + classpathResourcePath + " missing");
                return -1;
            }
            try (InputStream in = resourceStream) {
                WavData wavData = decodeWav(in);
                int buffer = AL10.alGenBuffers();
                AL10.alBufferData(buffer, wavData.alFormat, wavData.data, wavData.sampleRate);
                audioBuffers.put(classpathResourcePath, buffer);
                addAudioEvent("LOAD_OK " + classpathResourcePath);
                return buffer;
            }
        } catch (Exception e) {
            log("Failed to load audio: " + classpathResourcePath + " (" + e.getMessage() + ")");
            addAudioEvent(LOAD_FAIL + classpathResourcePath + " " + e.getMessage());
            return -1;
        }
    }

    private File resolveAudioFile(String classpathResourcePath) {
        String normalizedPath = classpathResourcePath.replace("\\", "/");
        if (normalizedPath.startsWith(RES_AUDIO_MUSIC)) {
            normalizedPath = normalizedPath.replaceFirst(RES_AUDIO_MUSIC, "Music/");
        } else if (normalizedPath.startsWith(RES_AUDIO_SFX)) {
            normalizedPath = normalizedPath.replaceFirst(RES_AUDIO_SFX, "Sfx/");
        }
        Path userDir = Path.of(System.getProperty("user.dir"));
        ArrayList<Path> candidates = new ArrayList<>();
        candidates.add(userDir.resolve("..").resolve(IXDARASSETS).resolve(normalizedPath).normalize());
        candidates.add(userDir.resolve(IXDARASSETS).resolve(normalizedPath).normalize());
        candidates.add(Path.of("C:/Code/IxdarAssets").resolve(normalizedPath).normalize());

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.toFile();
            }
        }
        return null;
    }

    private WavData decodeWav(InputStream sourceStream) throws Exception {
        try (AudioInputStream inputStream = javax.sound.sampled.AudioSystem.getAudioInputStream(sourceStream)) {
            AudioFormat base = inputStream.getFormat();
            AudioFormat decoded = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(),
                    NUM_16,
                    base.getChannels(),
                    base.getChannels() * 2,
                    base.getSampleRate(),
                    false);
            try (AudioInputStream pcmStream = javax.sound.sampled.AudioSystem.getAudioInputStream(decoded, inputStream)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] tmp = new byte[NUM_4096];
                int read = 0;
                while ((read = pcmStream.read(tmp)) != -1) {
                    output.write(tmp, 0, read);
                }
                byte[] bytes = output.toByteArray();
                ByteBuffer buffer = BufferUtils.createByteBuffer(bytes.length);
                buffer.put(bytes);
                buffer.flip();
                int format = decoded.getChannels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                return new WavData(buffer, format, (int) decoded.getSampleRate());
            }
        }
    }

    private void cleanupFinishedTransientSources() {
        if (!available || transientSources.isEmpty()) {
            return;
        }
        ArrayList<Integer> toRemove = new ArrayList<>();
        for (int source : transientSources) {
            int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                AL10.alDeleteSources(source);
                toRemove.add(source);
            }
        }
        transientSources.removeAll(toRemove);
    }

    private float clamp(float value) {
        if (value < NUM_0) {
            return NUM_0;
        }
        if (value > NUM_1) {
            return NUM_1;
        }
        return value;
    }

    private void cleanupOpenAL() {
        if (context != 0L) {
            ALC10.alcDestroyContext(context);
            context = 0L;
        }
        if (device != 0L) {
            ALC10.alcCloseDevice(device);
            device = 0L;
        }
    }

    private void log(String message) {
        try {
            Platforms.get().log(AUDIO + message);
        } catch (Exception e) {
            System.out.println(AUDIO + message);
        }
    }

    private void addAudioEvent(String event) {
        eventCounter++;
        eventLog.add(String.format("%d|%s|%s", eventCounter, Long.toString(System.currentTimeMillis()), event));
        if (eventLog.size() > NUM_200) {
            eventLog.remove(0);
        }
    }

    private static class WavData {
        private final ByteBuffer data;
        private final int alFormat;
        private final int sampleRate;

        private WavData(ByteBuffer data, int alFormat, int sampleRate) {
            this.data = data;
            this.alFormat = alFormat;
            this.sampleRate = sampleRate;
        }
    }
}
