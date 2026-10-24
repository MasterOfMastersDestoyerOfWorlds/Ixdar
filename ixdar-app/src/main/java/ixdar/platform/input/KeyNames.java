package ixdar.platform.input;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses the human key syntax the automation surface speaks — {@code ESCAPE}, {@code GRAVE},
 * {@code SHIFT+P}, {@code CTRL+SHIFT+S} — into {@link Keys} codes and a GLFW modifier mask.
 */
public final class KeyNames {

    public static final int MOD_SHIFT = 0x0001;

    public static final int MOD_CONTROL = 0x0002;

    public static final int MOD_ALT = 0x0004;

    public static final int MOD_SUPER = 0x0008;

    public static final String ACTION_TAP = "tap";

    public static final String ACTION_PRESS = "press";

    public static final String ACTION_RELEASE = "release";

    public static final String ACTION_REPEAT = "repeat";

    public static final String SHIFT = "SHIFT";

    public static final String CTRL = "CTRL";

    public static final String CONTROL = "CONTROL";

    public static final String ALT = "ALT";

    public static final String SUPER = "SUPER";

    public static final Map<String, Integer> MODIFIER_BITS = modifierBits();

    public static final Map<String, Integer> MODIFIER_KEYS = modifierKeys();

    public static final Map<String, Integer> KEY_CODES = keyCodes();

    private static final String ACTION_PREFIX = "ACTION_";

    private static final String MOUSE_PREFIX = "MOUSE_";

    private static final String SPEC_SEPARATOR = "\\+";

    private static final String NAME_LIST_SEPARATOR = ", ";

    private KeyNames() {
    }

    private static Map<String, Integer> modifierBits() {
        Map<String, Integer> bits = new LinkedHashMap<>();
        bits.put(SHIFT, MOD_SHIFT);
        bits.put(CTRL, MOD_CONTROL);
        bits.put(CONTROL, MOD_CONTROL);
        bits.put(ALT, MOD_ALT);
        bits.put(SUPER, MOD_SUPER);
        return Collections.unmodifiableMap(bits);
    }

    private static Map<String, Integer> modifierKeys() {
        Map<String, Integer> keys = new LinkedHashMap<>();
        keys.put(SHIFT, Keys.LEFT_SHIFT);
        keys.put(CTRL, Keys.LEFT_CONTROL);
        keys.put(CONTROL, Keys.LEFT_CONTROL);
        keys.put(ALT, Keys.LEFT_ALT);
        keys.put(SUPER, Keys.LEFT_SUPER);
        return Collections.unmodifiableMap(keys);
    }

    private static Map<String, Integer> keyCodes() {
        Map<String, Integer> codes = new LinkedHashMap<>();
        for (Field field : Keys.class.getFields()) {
            boolean constant = Modifier.isStatic(field.getModifiers()) && field.getType() == int.class;
            if (!constant || field.getName().startsWith(ACTION_PREFIX)
                    || field.getName().startsWith(MOUSE_PREFIX)) {
                continue;
            }
            try {
                codes.put(field.getName(), field.getInt(null));
            } catch (IllegalAccessException unreachable) {
                continue;
            }
        }
        for (char digit = '0'; digit <= '9'; digit++) {
            codes.put(String.valueOf(digit), (int) digit);
        }
        return Collections.unmodifiableMap(codes);
    }

    /**
     * Resolve the key half of a spec: the token after the last {@code +}.
     *
     * @param spec key spec such as {@code ESCAPE}, {@code P} or {@code CTRL+SHIFT+S}
     * @throws IllegalArgumentException when the spec is blank, names an unknown key, or is a
     *         bare GLFW integer, which is no longer accepted
     * @return the {@link Keys} code the spec names
     */
    public static int keyCode(String spec) {
        String token = keyToken(spec);
        if (token.length() > 1 && isNumeric(token)) {
            throw new IllegalArgumentException("key " + token + " is a raw GLFW code; use a name such as "
                    + "ESCAPE, GRAVE, RIGHT_BRACKET, P, or SHIFT+P");
        }
        Integer code = KEY_CODES.get(token);
        if (code == null) {
            throw new IllegalArgumentException("unknown key " + token + "; known keys: " + knownKeyNames());
        }
        return code;
    }

    /**
     * The modifier words of a key spec, its {@code +}-separated tokens before the key, or of a bare
     * list of modifiers held around an input with no key, such as a click.
     *
     * @param text      a key spec such as {@code CTRL+SHIFT+S}, or modifier words such as
     *                  {@code shift} or {@code ctrl+shift}; blank for none
     * @param endsInKey whether the last token is the key, as in a spec, rather than a modifier
     * @throws IllegalArgumentException when a modifier token is not a modifier word
     * @return the upper-case modifier words, in order
     */
    public static List<String> modifiers(String text, boolean endsInKey) {
        List<String> words = new ArrayList<>();
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return words;
        }
        String[] tokens = trimmed.split(SPEC_SEPARATOR);
        for (int index = 0; index < tokens.length - (endsInKey ? 1 : 0); index++) {
            String token = tokens[index].trim().toUpperCase(Locale.ROOT);
            if (!MODIFIER_BITS.containsKey(token)) {
                throw new IllegalArgumentException("unknown modifier " + token + "; use "
                        + String.join(NAME_LIST_SEPARATOR, MODIFIER_BITS.keySet()));
            }
            words.add(token);
        }
        return words;
    }

    /**
     * The GLFW modifier mask of modifier words.
     *
     * @param modifiers words as {@link #modifiers} parses them
     * @return the OR of every named modifier bit, or 0 for none
     */
    public static int modifierMask(List<String> modifiers) {
        int mask = 0;
        for (String modifier : modifiers) {
            mask |= MODIFIER_BITS.get(modifier);
        }
        return mask;
    }

    /**
     * The physical modifier keys to hold for modifier words, so a handler reading pressed keys sees
     * the same thing as one reading the modifier mask.
     *
     * @param modifiers words as {@link #modifiers} parses them
     * @return {@link Keys} codes for the named modifiers, in order, each once
     */
    public static List<Integer> modifierKeyCodes(List<String> modifiers) {
        List<Integer> codes = new ArrayList<>();
        for (String modifier : modifiers) {
            int code = MODIFIER_KEYS.get(modifier);
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return codes;
    }

    /**
     * Map an action word onto the GLFW action code delivered to a key callback.
     *
     * @param action one of {@code press}, {@code release} or {@code repeat}; {@code tap} is the
     *        caller's business, since it is a press and a release
     * @throws IllegalArgumentException when the word is not a known action
     * @return {@link Keys#ACTION_PRESS}, {@link Keys#ACTION_RELEASE} or {@link Keys#ACTION_REPEAT}
     */
    public static int actionCode(String action) {
        String word = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (ACTION_PRESS.equals(word)) {
            return Keys.ACTION_PRESS;
        }
        if (ACTION_RELEASE.equals(word)) {
            return Keys.ACTION_RELEASE;
        }
        if (ACTION_REPEAT.equals(word)) {
            return Keys.ACTION_REPEAT;
        }
        throw new IllegalArgumentException("unknown action " + action + "; use tap, press, release or repeat");
    }

    /**
     * Every key name the parser accepts, comma separated, for error messages and route docs.
     *
     * @return the sorted key names
     */
    public static String knownKeyNames() {
        List<String> names = new ArrayList<>(KEY_CODES.keySet());
        Collections.sort(names);
        return String.join(NAME_LIST_SEPARATOR,names);
    }

    private static String keyToken(String spec) {
        String trimmed = spec == null ? "" : spec.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("a key is required, e.g. ESCAPE, GRAVE or SHIFT+P");
        }
        String[] tokens = trimmed.split(SPEC_SEPARATOR);
        String token = tokens[tokens.length - 1].trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) {
            throw new IllegalArgumentException("key spec " + spec + " ends in a modifier; name the key too");
        }
        return token;
    }

    private static boolean isNumeric(String token) {
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
