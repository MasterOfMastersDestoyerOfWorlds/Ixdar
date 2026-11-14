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
        assertEquals(Keys.ESCAPE.keyCode, KeyNames.keyCode("ESCAPE"));
        assertEquals(Keys.GRAVE.keyCode, KeyNames.keyCode("grave"));
        assertEquals(Keys.RIGHT_BRACKET.keyCode, KeyNames.keyCode("Right_Bracket"));
        assertEquals(Keys.P.keyCode, KeyNames.keyCode(" p "));
        assertEquals('7', KeyNames.keyCode("7"));
    }

    @Test
    void readsModifiersOffTheSpec() {
        assertEquals(Keys.P.keyCode, KeyNames.keyCode(SHIFT_P));
        assertEquals(KeyNames.MOD_SHIFT, KeyNames.modifierMask(KeyNames.modifiers(SHIFT_P, true)));
        assertEquals(0, KeyNames.modifierMask(KeyNames.modifiers("P", true)));
        assertEquals(KeyNames.MOD_CONTROL | KeyNames.MOD_SHIFT,
                KeyNames.modifierMask(KeyNames.modifiers(CTRL_SHIFT_S, true)));
        assertEquals(Keys.S.keyCode, KeyNames.keyCode(CTRL_SHIFT_S));
        assertEquals(List.of(Keys.LEFT_CONTROL.keyCode, Keys.LEFT_SHIFT.keyCode),
                KeyNames.modifierKeyCodes(KeyNames.modifiers(CTRL_SHIFT_S, true)));
    }

    @Test
    void readsABareModifierListHeldAroundAClick() {
        assertEquals(List.of(KeyNames.SHIFT), KeyNames.modifiers(" shift ", false));
        assertEquals(KeyNames.MOD_CONTROL | KeyNames.MOD_ALT,
                KeyNames.modifierMask(KeyNames.modifiers("ctrl+alt", false)));
        assertEquals(List.of(), KeyNames.modifiers("", false));
        assertThrows(IllegalArgumentException.class, () -> KeyNames.modifiers("shift+P", false));
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
        assertThrows(IllegalArgumentException.class, () -> KeyNames.modifiers("META+P", true));
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
