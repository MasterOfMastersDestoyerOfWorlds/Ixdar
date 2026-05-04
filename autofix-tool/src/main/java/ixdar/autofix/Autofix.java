package ixdar.autofix;

import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.config.Environment;
import org.openrewrite.java.JavaParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class Autofix {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Autofix <checkstyle-result.xml> <module-base-dir>");
            System.exit(1);
        }
        Path xmlPath = Path.of(args[0]);
        Path baseDir = Path.of(args[1]);

        if (!Files.exists(xmlPath)) {
            System.out.println("No checkstyle output at " + xmlPath + ", nothing to fix.");
            return;
        }

        Map<String, Set<String>> rulesByFile = parseViolations(xmlPath);
        if (rulesByFile.isEmpty()) {
            System.out.println("No violations.");
            return;
        }

        // Group files that share the same set of rules so we parse + run once per
        // group.
        Map<Set<String>, List<Path>> grouped = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : rulesByFile.entrySet()) {
            grouped.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(Path.of(e.getKey()));
        }

        for (Map.Entry<Set<String>, List<Path>> entry : grouped.entrySet()) {
            List<Recipe> recipes = recipesFor(entry.getKey());
            if (recipes.isEmpty())
                continue;
            runRecipes(entry.getValue(), recipes, baseDir);
        }
    }

    private static void runRecipes(List<Path> files, List<Recipe> recipes, Path baseDir)
            throws IOException {
        ExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);
        JavaParser parser = JavaParser.fromJavaVersion().build();

        List<SourceFile> sources = parser.parse(files, baseDir, ctx)
                .collect(Collectors.toList());

        for (Recipe recipe : recipes) {
            List<Result> results = recipe.run(
                    new org.openrewrite.internal.InMemoryLargeSourceSet(sources), ctx).getChangeset().getAllResults();

            for (Result r : results) {
                if (r.getAfter() == null || r.getBefore() == null)
                    continue;
                Path target = baseDir.resolve(r.getAfter().getSourcePath());
                Files.writeString(target, r.getAfter().printAll());
                System.out.println("  fixed: " + target);
            }

            // Feed results back in so subsequent recipes see updated sources.
            sources = results.stream()
                    .map(r -> r.getAfter() != null ? r.getAfter() : findOriginal(sources, r))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
    }

    private static SourceFile findOriginal(List<SourceFile> sources, Result r) {
        return sources.stream()
                .filter(s -> r.getBefore() != null
                        && s.getSourcePath().equals(r.getBefore().getSourcePath()))
                .findFirst()
                .orElse(null);
    }

    private static List<Recipe> recipesFor(Set<String> rules) {
        Environment env = Environment.builder().scanRuntimeClasspath().build();
        List<Recipe> recipes = new ArrayList<>();

        if (rules.contains("DeclarationOrderCheck")) {
            recipes.add(env.activateRecipes(
                    "org.openrewrite.staticanalysis.DeclarationOrder"));
        }
        // Add custom recipes here as you write them:
        // if (rules.contains("MagicNumberCheck")) recipes.add(new
        // ExtractMagicNumbers());
        // if (rules.contains("JavadocMethodCheck")) recipes.add(new
        // AddMissingJavadoc());

        return recipes;
    }

    private static Map<String, Set<String>> parseViolations(Path xmlPath) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(xmlPath.toFile());
        NodeList files = doc.getElementsByTagName("file");
        Map<String, Set<String>> out = new LinkedHashMap<>();

        for (int i = 0; i < files.getLength(); i++) {
            Element fileElem = (Element) files.item(i);
            String name = fileElem.getAttribute("name");
            NodeList errors = fileElem.getElementsByTagName("error");
            Set<String> rules = new HashSet<>();
            for (int j = 0; j < errors.getLength(); j++) {
                String source = ((Element) errors.item(j)).getAttribute("source");
                rules.add(source.substring(source.lastIndexOf('.') + 1));
            }
            if (!rules.isEmpty())
                out.put(name, rules);
        }
        return out;
    }
}