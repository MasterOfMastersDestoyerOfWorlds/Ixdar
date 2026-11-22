package ixdar.canvas;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import ixdar.automation.AutomationPortFile;
import one.profiler.AsyncProfiler;

/**
 * Profiles a desktop launch from inside the app with async-profiler's Java API, so launch entries
 * carry no {@code -agentpath}, which is fatal where the library is missing, as on Windows.
 */
public final class LaunchProfiler {

    public static final String AGENT_PATH_PREFIX = "-agentpath:";

    public static final String PROFILER_LIBRARY_MARK = "asyncProfiler";

    public static final String LINKED_LIBRARY = ".profiler/libasyncProfiler";

    public static final String CAPTURE_DIRECTORY = "tmp/profiles";

    public static final String CAPTURE_LABEL = "f5";

    public static final String CAPTURE_TIME_FORMAT = "yyyyMMdd-HHmmss";

    public static final String LOG_PREFIX = "[profiler] ";

    /** Where a launch's profile comes from. */
    public enum Source {
        /** An async-profiler agent was passed on the command line, as ixdar-cli does; leave it. */
        AGENT_ALREADY_LOADED,
        /** The checkout's {@code .profiler} link, made by {@code ixdar-cli link-profiler}. */
        LINKED_LIBRARY,
        /** The library bundled in the async-profiler jar, for Linux and macOS. */
        BUNDLED_LIBRARY
    }

    private LaunchProfiler() {
    }

    /**
     * Which profiler a launch uses, given its JVM arguments and whether the checkout's link exists.
     *
     * @param jvmArguments the JVM's input arguments
     * @param linkedLibraryExists whether {@link #LINKED_LIBRARY} is on disk
     * @return the source to profile from
     */
    public static Source decide(List<String> jvmArguments, boolean linkedLibraryExists) {
        for (String argument : jvmArguments) {
            if (argument.startsWith(AGENT_PATH_PREFIX) && argument.contains(PROFILER_LIBRARY_MARK)) {
                return Source.AGENT_ALREADY_LOADED;
            }
        }
        return linkedLibraryExists ? Source.LINKED_LIBRARY : Source.BUNDLED_LIBRARY;
    }

    /**
     * Start cpu profiling unless an agent already runs, writing {@code tmp/profiles/f5-<time>-<pid>.html}
     * at exit; where no library loads, as on Windows, log one line and run unprofiled.
     */
    public static void startUnlessAttached() {
        Path root = AutomationPortFile.checkoutRoot();
        File linked = root.resolve(LINKED_LIBRARY).toFile();
        Source source = decide(ManagementFactory.getRuntimeMXBean().getInputArguments(), linked.exists());
        if (source == Source.AGENT_ALREADY_LOADED) {
            return;
        }
        AsyncProfiler profiler;
        try {
            profiler = source == Source.LINKED_LIBRARY ? AsyncProfiler.getInstance(linked.getAbsolutePath())
                    : AsyncProfiler.getInstance();
            profiler.execute("start,event=cpu");
        } catch (LinkageError | RuntimeException | IOException unavailable) {
            System.out.println(LOG_PREFIX + "unavailable here, running unprofiled: " + unavailable);
            return;
        }
        Path capture = root.resolve(CAPTURE_DIRECTORY).resolve(CAPTURE_LABEL + "-"
                + new SimpleDateFormat(CAPTURE_TIME_FORMAT).format(new Date()) + "-"
                + ProcessHandle.current().pid() + ".html");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.createDirectories(capture.getParent());
                profiler.execute("stop,file=" + capture);
            } catch (IOException | RuntimeException failed) {
                System.err.println(LOG_PREFIX + "could not write " + capture + ": " + failed);
            }
        }, "launch-profiler-dump"));
        System.out.println(LOG_PREFIX + "profiling to " + capture);
    }
}
