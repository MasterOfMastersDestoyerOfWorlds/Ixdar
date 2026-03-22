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

    public String defaultCanonicalId() {
        return defaultCanonicalId;
    }

    public List<String> canonicalIds() {
        return canonicalIds;
    }

    public Map<String, String> aliasToCanonical() {
        return aliasToCanonical;
    }

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
