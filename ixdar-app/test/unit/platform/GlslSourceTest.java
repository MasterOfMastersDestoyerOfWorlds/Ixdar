package unit.platform;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import ixdar.platform.gl.GlslSource;

class GlslSourceTest {

    @Test
    void adapt_rewritesVersionAndStripsPrecision() {
        String in = "#version 300 es\nprecision highp float;\nin vec3 a;\n";
        String out = GlslSource.adaptEs300SharedForDesktopCore330(in);
        Assertions.assertTrue(out.startsWith("#version 330 core\n"));
        Assertions.assertFalse(out.contains("precision"));
        Assertions.assertTrue(out.contains("in vec3 a;"));
    }
}
