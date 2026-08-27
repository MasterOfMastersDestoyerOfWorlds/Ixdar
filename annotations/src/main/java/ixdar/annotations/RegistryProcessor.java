package ixdar.annotations;

import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public abstract class RegistryProcessor extends AbstractProcessor {
    /** Simple-name suffix of the desktop-only registry class. */
    public static final String DESKTOP_SUFFIX = "Desktop";

    private static final String STR = "_";

    private boolean generated;
    private String fqcn;
    private Class<? extends Annotation> annotationClass;
    private String typeFqcn;
    private String collectionName;
    private boolean partitionDesktopOnly;

    /**
     * Configure the processor to scan for {@code annotationClass} and emit a
     * registry class whose {@code MAP} is keyed by id and produces instances of
     * {@code typeClass}.
     *
     * @param annotationClass marker annotation whose {@code @interface} declares an
     *                        {@code id} element; every class carrying this
     *                        annotation contributes one entry to the generated map
     * @param typeClass       common supertype of the registered classes; used as
     *                        the value bound on the generated
     *                        {@code Map<String, Supplier<? extends T>>}
     * @param collectionName  suffix appended to the processor's class name to form
     *                        the generated registry's simple name (e.g.
     *                        {@code "Commands"} yields
     *                        {@code XxxRegistry_Commands})
     */
    public RegistryProcessor(Class<? extends Annotation> annotationClass, Class<?> typeClass,
            String collectionName) {
        this(annotationClass, typeClass, collectionName, false);
    }

    /**
     * Configure the processor as above, optionally splitting {@code desktopOnly} entries into a
     * second {@code ..._<collection>Desktop} class so a web build never references them.
     *
     * @param annotationClass marker annotation whose {@code @interface} declares an {@code id}
     * @param typeClass common supertype of the registered classes
     * @param collectionName suffix forming the generated registry's simple name
     * @param partitionDesktopOnly whether to honour a boolean {@code desktopOnly} element
     */
    public RegistryProcessor(Class<? extends Annotation> annotationClass, Class<?> typeClass,
            String collectionName, boolean partitionDesktopOnly) {
        this(annotationClass, typeClass.getCanonicalName(), collectionName, partitionDesktopOnly);
    }

    /**
     * Configure the processor with the registered supertype named by fully
     * qualified name, for supertypes living outside this module (the processor
     * only ever emits the name into generated source).
     *
     * @param annotationClass marker annotation whose {@code @interface} declares an {@code id}
     * @param typeFqcn fully qualified name of the registered classes' common supertype
     * @param collectionName suffix forming the generated registry's simple name
     * @param partitionDesktopOnly whether to honour a boolean {@code desktopOnly} element
     */
    public RegistryProcessor(Class<? extends Annotation> annotationClass, String typeFqcn,
            String collectionName, boolean partitionDesktopOnly) {
        this.fqcn = this.getClass().getCanonicalName();
        this.annotationClass = annotationClass;
        this.typeFqcn = typeFqcn;
        this.collectionName = collectionName;
        this.partitionDesktopOnly = partitionDesktopOnly;
    }

    /**
     * Generates one registry source file from annotated classes, keyed by explicit
     * annotation id or the class name.
     *
     * @param annotations annotation types requested for this round (unused; the
     *                    configured {@code annotationClass} drives discovery)
     * @param roundEnv    round environment used to collect elements annotated with
     *                    the configured annotation
     * @return {@code true} to claim the supported annotations for this round
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        if (generated) {
            return false;
        }
        try {
            Map<String, String> mainEntries = new TreeMap<>();
            Map<String, String> desktopEntries = new TreeMap<>();
            Map<String, String> seenIds = new HashMap<>();
            for (Element element : roundEnv.getElementsAnnotatedWith(annotationClass)) {
                if (element.getKind() != ElementKind.CLASS) {
                    continue;
                }
                String fqClassName = ((TypeElement) element).getQualifiedName().toString();
                String id = element.getSimpleName().toString();
                boolean desktopOnly = false;
                String annotationName = annotationClass.getName();
                for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                    if (mirror.getAnnotationType().toString().equals(annotationName)) {
                        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror
                                .getElementValues().entrySet()) {
                            String key = entry.getKey().getSimpleName().toString();
                            Object val = entry.getValue().getValue();
                            if (key.equals("id") && val != null && !val.toString().isBlank()) {
                                id = val.toString();
                            }
                            if (key.equals("desktopOnly") && Boolean.TRUE.equals(val)) {
                                desktopOnly = true;
                            }
                        }
                    }
                }
                String previous = seenIds.put(id, fqClassName);
                if (previous != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Duplicate registry id \"" + id + "\" on " + fqClassName + " and " + previous
                                    + "; set a distinct id() on the annotation so neither is silently dropped.",
                            element);
                }
                if (partitionDesktopOnly && desktopOnly) {
                    desktopEntries.put(id, fqClassName);
                } else {
                    mainEntries.put(id, fqClassName);
                }
            }
            String genClassName = this.getClass().getSimpleName() + STR + this.collectionName;
            writeRegistryClass(genClassName, mainEntries,
                    partitionDesktopOnly ? desktopEntries.keySet() : null);
            if (partitionDesktopOnly) {
                writeRegistryClass(genClassName + DESKTOP_SUFFIX, desktopEntries, null);
            }
            generated = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Emit one registry source file mapping id to constructor reference, optionally listing the ids
     * routed to the desktop-only sibling as a plain string set safe for web reachability.
     *
     * @param genClassName simple name of the generated class
     * @param entries id-to-class entries the class's {@code MAP} holds
     * @param desktopOnlyIds ids emitted as {@code DESKTOP_ONLY_IDS}, or null to omit the set
     * @throws Exception if the source file cannot be written
     */
    private void writeRegistryClass(String genClassName, Map<String, String> entries,
            Set<String> desktopOnlyIds) throws Exception {
        JavaFileObject file = processingEnv.getFiler()
                .createSourceFile(this.getClass().getPackageName() + "." + genClassName);
        try (Writer out = file.openWriter()) {
            out.write("package " + this.getClass().getPackageName() + ";\n\n");
            out.write("import java.util.*;\n");
            out.write("import " + typeFqcn + ";\n");
            out.write("import java.util.function.Supplier;\n\n");
            out.write("public final class " + genClassName + " {\n");
            out.write("\tpublic static final Map<String, Supplier<? extends " + typeFqcn
                    + ">> MAP = new HashMap<>();\n\n");
            if (desktopOnlyIds != null) {
                StringBuilder ids = new StringBuilder();
                for (String id : desktopOnlyIds) {
                    if (ids.length() > 0) {
                        ids.append(", ");
                    }
                    ids.append('"').append(id).append('"');
                }
                out.write("\tpublic static final Set<String> DESKTOP_ONLY_IDS = Set.of(" + ids + ");\n\n");
            }
            out.write("\tstatic {\n");
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.write("\t\tMAP.put(\"" + entry.getKey() + "\", " + entry.getValue() + "::new);\n");
            }
            out.write("\t}\n");
            out.write("}\n");
        }
    }
}
