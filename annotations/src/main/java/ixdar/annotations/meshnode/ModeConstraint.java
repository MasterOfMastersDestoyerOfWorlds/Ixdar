package ixdar.annotations.meshnode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Declares allowed canonical mode strings for a STRING input port, plus optional aliases.
 * Normalization is trim + uppercase; stored values are canonical IDs.
 */
public final class ModeConstraint {

    private final String defaultCanonicalId;
    private final List<String> canonicalIds;
    private final Map<String, String> aliasToCanonical;

    /**
     * Build a constraint from a canonical-id set and an alias map. All ids are normalized
     * (trimmed and uppercased) before storage.
     *
     * @param defaultCanonicalId canonical id used when the input value is null or empty;
     *                           must appear in {@code canonicalIds} after normalization
     * @param canonicalIds allowed canonical ids; must be non-empty
     * @param aliases mapping of alias to canonical id; every target must appear in {@code canonicalIds}
     * @throws IllegalArgumentException if {@code canonicalIds} is empty, if {@code defaultCanonicalId}
     *         is not present in {@code canonicalIds}, or if any alias targets an id not in {@code canonicalIds}
     */
    public ModeConstraint(String defaultCanonicalId, List<String> canonicalIds, Map<String, String> aliases) {
        Objects.requireNonNull(defaultCanonicalId, "defaultCanonicalId");
        Objects.requireNonNull(canonicalIds, "canonicalIds");
        Objects.requireNonNull(aliases, "aliases");
        if (canonicalIds.isEmpty()) {
            throw new IllegalArgumentException("canonicalIds must not be empty");
        }
        this.defaultCanonicalId = defaultCanonicalId.trim().toUpperCase(Locale.ROOT);
        ArrayList<String> canon = new ArrayList<>();
        for (String id : canonicalIds) {
            canon.add(id.trim().toUpperCase(Locale.ROOT));
        }
        this.canonicalIds = Collections.unmodifiableList(canon);
        if (!this.canonicalIds.contains(this.defaultCanonicalId)) {
            throw new IllegalArgumentException(
                    "defaultCanonicalId '" + this.defaultCanonicalId + "' not in canonicalIds " + this.canonicalIds);
        }
        HashMap<String, String> aliasMap = new HashMap<>();
        for (Map.Entry<String, String> e : aliases.entrySet()) {
            String key = e.getKey().trim().toUpperCase(Locale.ROOT);
            String val = e.getValue().trim().toUpperCase(Locale.ROOT);
            if (!this.canonicalIds.contains(val)) {
                throw new IllegalArgumentException("alias target '" + val + "' not in canonicalIds");
            }
            aliasMap.put(key, val);
        }
        this.aliasToCanonical = Collections.unmodifiableMap(aliasMap);
    }

    /**
     * Default canonical id, in normalized (trimmed, uppercased) form.
     *
     * @return the default canonical id used when no value is supplied
     */
    public String defaultCanonicalId() {
        return defaultCanonicalId;
    }

    /**
     * Allowed canonical ids in declaration order, normalized and immutable.
     *
     * @return unmodifiable list of canonical ids
     */
    public List<String> canonicalIds() {
        return canonicalIds;
    }

    /**
     * Alias to canonical-id lookup, normalized and immutable.
     *
     * @return unmodifiable map from alias to canonical id
     */
    public Map<String, String> aliasToCanonical() {
        return aliasToCanonical;
    }

    /**
     * Normalize {@code raw} to a canonical id: trim and uppercase, resolve aliases, and
     * fall back to the default for null or empty input.
     *
     * @param raw value to normalize (may be null)
     * @throws IllegalArgumentException if {@code raw} matches neither a canonical id nor a registered alias
     * @return matching canonical id, or {@link #defaultCanonicalId()} if {@code raw} is null/blank
     */
    public String normalize(String raw) {
        if (raw == null) {
            return defaultCanonicalId;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            return defaultCanonicalId;
        }
        if (aliasToCanonical.containsKey(key)) {
            return aliasToCanonical.get(key);
        }
        if (canonicalIds.contains(key)) {
            return key;
        }
        throw new IllegalArgumentException(
                "Unknown mode '" + raw + "'. Allowed canonical values: " + canonicalIds
                        + (aliasToCanonical.isEmpty() ? "" : "; aliases: " + aliasToCanonical.keySet()));
    }

    /**
     * Validate that a port's declared default value is compatible with this constraint:
     * it must be a String that {@link #normalize(String)} accepts. Null is treated as
     * "no default supplied" and passes.
     *
     * @param defaultValue declared default for the owning port (may be null)
     * @throws IllegalArgumentException if {@code defaultValue} is non-null and not a String,
     *         or if its string form is not a known canonical id or alias
     */
    public void validateDefault(Object defaultValue) {
        if (defaultValue == null) {
            return;
        }
        if (!(defaultValue instanceof String)) {
            throw new IllegalArgumentException("Mode port default must be a String");
        }
        normalize((String) defaultValue);
    }
}
