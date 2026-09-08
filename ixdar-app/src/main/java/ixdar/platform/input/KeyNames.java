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

    /** GLFW modifier bit for shift. */
    public static final int MOD_SHIFT = 0x0001;

    /** GLFW modifier bit for control. */
    public static final int MOD_CONTROL = 0x0002;

    /** GLFW modifier bit for alt. */
    public static final int MOD_ALT = 0x0004;

    /** GLFW modifier bit for the super / command key. */
    public static final int MOD_SUPER = 0x0008;

    /** Action word for a press immediately followed by a release; the default. */
    public static final String ACTION_TAP = "tap";

    /** Action word for a key-down with no matching key-up. */
    public static final String ACTION_PRESS = "press";

    /** Action word for a key-up. */
    public static final String ACTION_RELEASE = "release";

    /** Action word for an auto-repeat key-down. */
    public static final String ACTION_REPEAT = "repeat";

    /** Modifier word for shift. */
    public static final String SHIFT = "SHIFT";

    /** Short modifier word for control. */
    public static final String CTRL = "CTRL";

    /** Long modifier word for control. */
    public static final String CONTROL = "CONTROL";

    /** Modifier word for alt. */
    public static final String ALT = "ALT";

    /** Modifier word for the super / command key. */
    public static final String SUPER = "SUPER";

    /** Modifier word to bit, in the order they are listed back to a caller. */
    public static final Map<String, Integer> MODIFIER_BITS = modifierBits();

    /** Modifier word to the physical key it holds down, so handlers reading pressed keys agree. */
    public static final Map<String, Integer> MODIFIER_KEYS = modifierKeys();

    /** Key name to code, reflected off {@link Keys} so the two can never drift apart. */
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
     * Resolve the GLFW modifier mask named by the {@code +}-separated prefix of a spec.
     *
     * @param spec key spec such as {@code CTRL+SHIFT+S}
     * @throws IllegalArgumentException when a prefix token is not a modifier word
     * @return the OR of every named modifier bit, or 0 when the spec names none
     */
    public static int modifierMask(String spec) {
        int mask = 0;
        for (String modifier : modifierTokens(spec)) {
            mask |= MODIFIER_BITS.get(modifier);
        }
        return mask;
    }

    /**
     * The physical modifier keys a spec asks to be held, so a handler reading pressed keys sees
     * the same thing as one reading the modifier mask.
     *
     * @param spec key spec such as {@code CTRL+SHIFT+S}
     * @throws IllegalArgumentException when a prefix token is not a modifier word
     * @return {@link Keys} codes for the named modifiers, in spec order
     */
    public static List<Integer> modifierKeyCodes(String spec) {
        List<Integer> codes = new ArrayList<>();
        for (String modifier : modifierTokens(spec)) {
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

    private static List<String> modifierTokens(String spec) {
        List<String> modifiers = new ArrayList<>();
        String trimmed = spec == null ? "" : spec.trim();
        String[] tokens = trimmed.split(SPEC_SEPARATOR);
        for (int index = 0; index < tokens.length - 1; index++) {
            String token = tokens[index].trim().toUpperCase(Locale.ROOT);
            if (!MODIFIER_BITS.containsKey(token)) {
                throw new IllegalArgumentException("unknown modifier " + token + "; use "
                        + String.join(NAME_LIST_SEPARATOR,MODIFIER_BITS.keySet()));
            }
            modifiers.add(token);
        }
        return modifiers;
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
