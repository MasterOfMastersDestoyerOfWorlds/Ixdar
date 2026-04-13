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
 * <h3>File format</h3>
 * A {@code .skill} file is a text file with a YAML-style metadata header followed
 * by DSL function code:
 * <pre>
 * ---
 * name: tapered_horn
 * description: Curve sweep with radius closure for horn/tusk/claw shapes
 * technique: radius_closure
 * fitness: 91.2
 * generation: 5
 * origin_run: skull-v2
 * ---
 * def tapered_horn(length: FLOAT, base_radius: FLOAT, tip_radius: FLOAT) -> MESH:
 *     path = curve_primitive_line(length=length)
 *     profile = circle_curve(radius=base_radius, segments=12)
 *     swept = curve_sweep(curve=path.curve, profile=profile.curve)
 * end
 * </pre>
 *
 * The metadata header is optional (for backward compatibility with plain DSL function files).
 */
public final class SkillLibrary {

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

        /** Format for LLM context injection: signature + description. */
        public String toPromptBlock() {
            StringBuilder sb = new StringBuilder();
            sb.append("- ").append(functionDef.name).append("(");
            for (int i = 0; i < functionDef.params.size(); i++) {
                if (i > 0) sb.append(", ");
                PythonParser.FunctionParam p = functionDef.params.get(i);
                sb.append(p.name).append(": ").append(p.type);
            }
            sb.append(") -> ").append(functionDef.returnType).append("\n");
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

    private final List<Skill> skills = new ArrayList<>();

    /** Load all .skill files from a directory. */
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
                    System.err.println("[SkillLibrary] Failed to load " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }
    }

    /** Load a single .skill file. */
    public static Skill loadSkillFile(Path file) throws IOException {
        String content = Files.readString(file);
        Map<String, String> metadata = new LinkedHashMap<>();
        String dslCode;

        if (content.startsWith("---")) {
            int endIdx = content.indexOf("---", 3);
            if (endIdx < 0) {
                throw new IOException("Unterminated metadata header in " + file);
            }
            String header = content.substring(3, endIdx).trim();
            dslCode = content.substring(endIdx + 3).trim();

            for (String line : header.split("\n")) {
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
        float fitness = 0f;
        try {
            fitness = Float.parseFloat(metadata.getOrDefault("fitness", "0"));
        } catch (NumberFormatException ignored) {}
        int generation = 0;
        try {
            generation = Integer.parseInt(metadata.getOrDefault("generation", "0"));
        } catch (NumberFormatException ignored) {}
        String originRun = metadata.getOrDefault("origin_run", "");

        return new Skill(name, description, technique, fitness, generation,
                originRun, dslCode, funcDef, file);
    }

    /** Get all loaded skills. */
    public List<Skill> getSkills() {
        return skills;
    }

    /** Register all loaded skills' function definitions with a runtime. */
    public void registerWith(NodeGraphRuntime runtime) {
        Map<String, PythonParser.FunctionDef> defs = new LinkedHashMap<>();
        for (Skill skill : skills) {
            defs.put(skill.functionDef.name, skill.functionDef);
        }
        runtime.registerFunctionDefs(defs);
    }

    /** Generate the "Available Skills" prompt block for LLM injection. */
    public String toPromptBlock() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills (reusable DSL functions)\n\n");
        for (Skill skill : skills) {
            sb.append(skill.toPromptBlock()).append("\n");
        }
        return sb.toString();
    }

    /** Export all skills as DSL function definition text (for inclusion in DSL files). */
    public String toDslPreamble() {
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            sb.append(skill.dslCode).append("\n\n");
        }
        return sb.toString();
    }
}
