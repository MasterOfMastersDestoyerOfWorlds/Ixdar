package unit.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ixdar.platform.input.KeyNames;
import ixdar.platform.input.Keys;

class KeyNamesTest {

    private static final String SHIFT_P = "SHIFT+P";

    private static final String CTRL_SHIFT_S = "ctrl+shift+S";

    @Test
    void resolvesNamedKeysCaseInsensitively() {
        assertEquals(Keys.ESCAPE, KeyNames.keyCode("ESCAPE"));
        assertEquals(Keys.GRAVE, KeyNames.keyCode("grave"));
        assertEquals(Keys.RIGHT_BRACKET, KeyNames.keyCode("Right_Bracket"));
        assertEquals(Keys.P, KeyNames.keyCode(" p "));
        assertEquals('7', KeyNames.keyCode("7"));
    }

    @Test
    void readsModifiersOffTheSpec() {
        assertEquals(Keys.P, KeyNames.keyCode(SHIFT_P));
        assertEquals(KeyNames.MOD_SHIFT, KeyNames.modifierMask(SHIFT_P));
        assertEquals(0, KeyNames.modifierMask("P"));
        assertEquals(KeyNames.MOD_CONTROL | KeyNames.MOD_SHIFT,
                KeyNames.modifierMask(CTRL_SHIFT_S));
        assertEquals(Keys.S, KeyNames.keyCode(CTRL_SHIFT_S));
        assertEquals(List.of(Keys.LEFT_CONTROL, Keys.LEFT_SHIFT),
                KeyNames.modifierKeyCodes(CTRL_SHIFT_S));
    }

    @Test
    void rejectsRawGlfwCodes() {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> KeyNames.keyCode("256"));
        assertTrue(refused.getMessage().contains("raw GLFW code"));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.keyCode("65"));
    }

    @Test
    void rejectsUnknownNamesAndModifiers() {
        assertThrows(IllegalArgumentException.class, () -> KeyNames.keyCode("ESCPAE"));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.keyCode(""));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.keyCode("SHIFT+"));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.modifierMask("META+P"));
    }

    @Test
    void mapsActionWordsOntoGlfwActions() {
        assertEquals(Keys.ACTION_PRESS, KeyNames.actionCode("press"));
        assertEquals(Keys.ACTION_RELEASE, KeyNames.actionCode("RELEASE"));
        assertEquals(Keys.ACTION_REPEAT, KeyNames.actionCode(" repeat "));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.actionCode("tap"));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.actionCode("1"));
    }
}
