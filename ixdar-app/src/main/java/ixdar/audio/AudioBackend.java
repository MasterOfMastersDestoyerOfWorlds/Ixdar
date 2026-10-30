package ixdar.audio;

/**
 * Sound playback, supplied by the platform rather than referenced directly.
 *
 * <p>The indirection is what keeps OpenAL out of the browser build: only the desktop and headless
 * backends name {@link AudioSystem}, so the web launcher never reaches it and TeaVM never links it.
 */
public interface AudioBackend {

    /**
     * Open the audio device; idempotent, and a failure disables audio rather than throwing.
     */
    void init();

    /**
     * Start (or resume) looping music, swapping tracks when the path changes.
     *
     * @param relativeAssetPath asset path of a WAV file, one of the {@link AudioAssets} constants
     */
    void playMenuMusicLoop(String relativeAssetPath);

    /**
     * Pause the music if it is playing.
     */
    void pauseMenuMusic();

    /**
     * Play a one-shot sound effect at a pitch; above 1 plays it higher and shorter.
     *
     * @param relativeAssetPath asset path of a WAV file, one of the {@link AudioAssets} constants
     * @param pitch             playback rate, 1 for as recorded
     */
    void playSfxOnce(String relativeAssetPath, float pitch);

    /**
     * Release every source and buffer and close the device; safe to call repeatedly.
     */
    void shutdown();
}
