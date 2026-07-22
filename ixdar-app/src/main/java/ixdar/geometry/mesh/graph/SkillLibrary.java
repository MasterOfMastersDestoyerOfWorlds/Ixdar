package ixdar.geometry.mesh.graph;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ixdar.parsing.python.PythonLexer;
import ixdar.parsing.python.PythonParser;

/**
 * Loads {@code .skill} files from a directory and provides their DSL function
 * definitions for injection into the node graph runtime and/or LLM context.
 *
 * <p>A {@code .skill} file is DSL function code, optionally preceded by a
 * {@code ---}-delimited YAML-style metadata header.
 */
public final class SkillLibrary {
    public static final String STR = ": ";
    public static final String N = "\n";
    public static final String STR_2 = "---";
    public static final String STR_0 = "0";
    public static final int NUM_3 = 3;
    public static final float NUM_0 = 0f;

    private final List<Skill> skills = new ArrayList<>();

    /**
     * Load all .skill files from a directory. Missing directories are silently
     * ignored. Per-file failures are logged to stderr and skipped — one bad file
     * doesn't fail the whole library.
     *
     * @param skillDir directory containing {@code *.skill} files
     * @throws IOException on directory traversal failure
     */
    public void loadDirectory(Path skillDir) throws IOException {
        if (!Files.isDirectory(skillDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillDir, "*.skill")) {
            for (Path file : stream) {
                try {
                    Skill skill = loadSkillFile(file);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } catch (Exception e) {
                    System.err.println("[SkillLibrary] Failed to load " + file.getFileName() + STR + e.getMessage());
                }
            }
        }
    }

    /**
     * Load a single .skill file. Parses the optional {@code ---}-delimited
     * metadata header, then runs the rest through the DSL parser to extract a
     * {@link PythonParser.FunctionDef}. Returns null when the body is empty.
     *
     * @param file path to a {@code .skill} file
     * @throws IOException on read failure or unterminated metadata header
     * @return loaded skill or null when no DSL body is present
     */
    public static Skill loadSkillFile(Path file) throws IOException {
        String content = Files.readString(file);
        Map<String, String> metadata = new LinkedHashMap<>();
        String dslCode;

        if (content.startsWith(STR_2)) {
            int endIdx = content.indexOf(STR_2, NUM_3);
            if (endIdx < 0) {
                throw new IOException("Unterminated metadata header in " + file);
            }
            String header = content.substring(NUM_3, endIdx).trim();
            dslCode = content.substring(endIdx + NUM_3).trim();

            for (String line : header.split(N)) {
                line = line.trim();
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    metadata.put(key, value);
                }
            }
        } else {
            dslCode = content.trim();
        }

        if (dslCode.isEmpty()) return null;

        // Parse the DSL to extract the function definition
        PythonParser parser = new PythonParser(new PythonLexer(dslCode));
        parser.parseGraph(); // processes function defs
        Map<String, PythonParser.FunctionDef> funcDefs = parser.functionDefs();

        if (funcDefs.isEmpty()) {
            throw new IOException("No function definition found in " + file);
        }

        // Use the first (and typically only) function def
        PythonParser.FunctionDef funcDef = funcDefs.values().iterator().next();

        String name = metadata.getOrDefault("name", funcDef.name);
        String description = metadata.getOrDefault("description", "");
        String technique = metadata.getOrDefault("technique", "");
        float fitness = NUM_0;
        try {
            fitness = Float.parseFloat(metadata.getOrDefault("fitness", STR_0));
        } catch (NumberFormatException ignored) {}
        int generation = 0;
        try {
            generation = Integer.parseInt(metadata.getOrDefault("generation", STR_0));
        } catch (NumberFormatException ignored) {}
        String originRun = metadata.getOrDefault("origin_run", "");

        return new Skill(name, description, technique, fitness, generation,
                originRun, dslCode, funcDef, file);
    }

    /**
     * Direct access to the loaded skill list.
     *
     * @return all skills loaded so far (live list; not a copy)
     */
    public List<Skill> getSkills() {
        return skills;
    }

    /**
     * Register all loaded skills' function definitions with a runtime so DSL
     * code can call them by name.
     *
     * @param runtime runtime to receive {@link NodeGraphRuntime#registerFunctionDefs}
     */
    public void registerWith(NodeGraphRuntime runtime) {
        Map<String, PythonParser.FunctionDef> defs = new LinkedHashMap<>();
        for (Skill skill : skills) {
            defs.put(skill.functionDef.name, skill.functionDef);
        }
        runtime.registerFunctionDefs(defs);
    }

    /**
     * Generate the "Available Skills" prompt block for LLM injection. Each skill
     * contributes its signature and description; empty when no skills loaded.
     *
     * @return markdown-formatted prompt section, or empty string
     */
    public String toPromptBlock() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills (reusable DSL functions)\n\n");
        for (Skill skill : skills) {
            sb.append(skill.toPromptBlock()).append(N);
        }
        return sb.toString();
    }

    /**
     * Export all skills as raw DSL function definition text, ready to prepend to
     * a generated DSL file so it can call any skill by name.
     *
     * @return concatenated DSL source (function defs separated by blank lines)
     */
    public String toDslPreamble() {
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            sb.append(skill.dslCode).append("\n\n");
        }
        return sb.toString();
    }

    /** A loaded skill: metadata + parsed function definition. */
    public static class Skill {
        public final String name;
        public final String description;
        public final String technique;
        public final float fitness;
        public final int generation;
        public final String originRun;
        public final String dslCode;
        public final PythonParser.FunctionDef functionDef;
        public final Path sourcePath;

        /**
         * Direct field constructor; fields are populated by
         * {@link SkillLibrary#loadSkillFile} from the metadata header (or
         * defaults) and the parsed function body.
         *
         * @param name        skill name (header {@code name:}, or function-def name)
         * @param description short prose describing what the skill produces
         * @param technique   evolution-search-friendly category tag
         * @param fitness     evaluator score from the run that produced this skill
         * @param generation  generation index in that run
         * @param originRun   identifier of the producing run
         * @param dslCode     full original DSL source (used by {@link #toDslPreamble})
         * @param functionDef parsed function definition (used by {@link NodeGraphRuntime})
         * @param sourcePath  filesystem location the skill was loaded from
         */
        public Skill(String name, String description, String technique, float fitness,
                int generation, String originRun, String dslCode,
                PythonParser.FunctionDef functionDef, Path sourcePath) {
            this.name = name;
            this.description = description;
            this.technique = technique;
            this.fitness = fitness;
            this.generation = generation;
            this.originRun = originRun;
            this.dslCode = dslCode;
            this.functionDef = functionDef;
            this.sourcePath = sourcePath;
        }

        /**
         * Format for LLM context injection: signature + description, optionally
         * appended with fitness and origin run.
         *
         * @return markdown bullet describing this skill
         */
        public String toPromptBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(functionDef.name).append("(");
            for (int i = 0; i < functionDef.params.size(); i++) {
                if (i > 0) sb.append(", ");
                PythonParser.FunctionParam p = functionDef.params.get(i);
                sb.append(p.name).append(STR).append(p.type);
            }
            sb.append(") -> ").append(functionDef.returnType).append(N);
            sb.append("  ").append(description);
            if (fitness > 0) {
                sb.append(String.format(" (fitness: %.1f%%", fitness));
                if (originRun != null && !originRun.isEmpty()) {
                    sb.append(", from ").append(originRun);
                }
                sb.append(")");
            }
            return sb.toString();
        }
    }
}
