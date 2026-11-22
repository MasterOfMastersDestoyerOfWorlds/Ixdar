package unit.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.canvas.LaunchProfiler;
import ixdar.canvas.LaunchProfiler.Source;

/**
 * TOOL-61: a launch profiles itself only when no async-profiler agent was passed in, preferring the
 * checkout's linked library to the one bundled in the jar.
 */
class LaunchProfilerTest {

    private static final String CLI_AGENT =
            "-agentpath:/repo/.profiler/libasyncProfiler=start,event=cpu,file=/repo/tmp/profiles/x-%p.html";

    @Test
    void anAgentOnTheCommandLineIsLeftToProfile() {
        assertEquals(Source.AGENT_ALREADY_LOADED, LaunchProfiler.decide(List.of("-Xmx4g", CLI_AGENT), true));
        assertEquals(Source.AGENT_ALREADY_LOADED, LaunchProfiler.decide(List.of(CLI_AGENT), false));
    }

    @Test
    void withoutAnAgentTheLinkedLibraryWinsOverTheBundledOne() {
        assertEquals(Source.LINKED_LIBRARY, LaunchProfiler.decide(List.of("-Xmx4g"), true));
        assertEquals(Source.BUNDLED_LIBRARY, LaunchProfiler.decide(List.of("-Xmx4g"), false));
    }

    @Test
    void anotherAgentIsNotMistakenForTheProfiler() {
        assertEquals(Source.BUNDLED_LIBRARY,
                LaunchProfiler.decide(List.of("-agentlib:jdwp=transport=dt_socket,server=n"), false));
    }
}
