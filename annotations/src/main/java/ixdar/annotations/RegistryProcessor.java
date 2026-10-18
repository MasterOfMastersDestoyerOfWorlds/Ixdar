package ixdar.annotations;

import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public abstract class RegistryProcessor extends AbstractProcessor {
    public static final String DESKTOP_SUFFIX = "Desktop";

    private static final String GENERATED_CLASS_NAME_SEPARATOR = "_";

    private boolean generated;
    private String fqcn;
    private Class<? extends Annotation> annotationClass;
    private String typeFqcn;
    private String typeSimpleName;
    private String collectionName;
    private boolean partitionDesktopOnly;
    private String hostSimpleName;
    private String hostBaseFqcn;

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
        this.fqcn = this.getClass().getCanonicalName();
        this.annotationClass = annotationClass;
        this.typeFqcn = typeClass.getCanonicalName();
        this.collectionName = collectionName;
        this.partitionDesktopOnly = partitionDesktopOnly;
    }

    /**
     * Configure the processor with the registered supertype named by simple name, for supertypes
     * living outside this module; its package is read off the annotated classes' own supertypes,
     * so moving it to another package cannot break the generated registry.
     *
     * @param annotationClass marker annotation whose {@code @interface} declares an {@code id}
     * @param typeSimpleName simple name of the registered classes' common supertype
     * @param collectionName suffix forming the generated registry's simple name
     * @param partitionDesktopOnly whether to honour a boolean {@code desktopOnly} element
     */
    public RegistryProcessor(Class<? extends Annotation> annotationClass, String typeSimpleName,
            String collectionName, boolean partitionDesktopOnly) {
        this.fqcn = this.getClass().getCanonicalName();
        this.annotationClass = annotationClass;
        this.typeSimpleName = typeSimpleName;
        this.collectionName = collectionName;
        this.partitionDesktopOnly = partitionDesktopOnly;
    }

    /**
     * Configure the processor for classes built for a host: each class's host is the parameter type
     * of its one public one-argument constructor, and the registry maps each id to a factory taking
     * the host and a test of which hosts fit.
     *
     * @param annotationClass marker annotation declaring {@code id}
     * @param typeSimpleName simple name of the registered classes' common supertype
     * @param hostSimpleName simple name of the hosts' common supertype, the factories' argument
     * @param collectionName suffix forming the generated registry's simple name
     */
    public RegistryProcessor(Class<? extends Annotation> annotationClass, String typeSimpleName,
            String hostSimpleName, String collectionName) {
        this(annotationClass, typeSimpleName, collectionName, false);
        this.hostSimpleName = hostSimpleName;
    }

    /**
     * Claim the configured annotation by its class literal, so moving the annotation cannot leave
     * the processor silently matching nothing.
     *
     * @return the configured annotation's canonical name
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(annotationClass.getCanonicalName());
    }

    /**
     * Track the compiler's own source level, so raising {@code <release>} in the poms never
     * leaves the processors warning that they only support an older one.
     *
     * @return the newest source version this compiler supports
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
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
            Map<String, String> hostById = new TreeMap<>();
            Map<String, String> seenIds = new HashMap<>();
            for (Element element : roundEnv.getElementsAnnotatedWith(annotationClass)) {
                if (element.getKind() != ElementKind.CLASS) {
                    continue;
                }
                String fqClassName = ((TypeElement) element).getQualifiedName().toString();
                Deque<TypeMirror> supertypes = new ArrayDeque<>(List.of(element.asType()));
                while (typeFqcn == null && !supertypes.isEmpty()) {
                    TypeMirror type = supertypes.pop();
                    Element supertype = processingEnv.getTypeUtils().asElement(type);
                    if (supertype.getSimpleName().contentEquals(typeSimpleName)) {
                        typeFqcn = ((TypeElement) supertype).getQualifiedName().toString();
                    }
                    supertypes.addAll(processingEnv.getTypeUtils().directSupertypes(type));
                }
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
                if (hostSimpleName != null) {
                    // The host is the parameter of the one public one-argument constructor, which
                    // the generated factory calls; the first host fixes the hosts' common supertype.
                    Types types = processingEnv.getTypeUtils();
                    TypeMirror host = null;
                    int hostConstructorCount = 0;
                    for (Element member : element.getEnclosedElements()) {
                        if (member.getKind() == ElementKind.CONSTRUCTOR
                                && member.getModifiers().contains(Modifier.PUBLIC)) {
                            List<? extends VariableElement> parameters = ((ExecutableElement) member).getParameters();
                            if (parameters.size() == 1) {
                                host = parameters.get(0).asType();
                                hostConstructorCount++;
                            }
                        }
                    }
                    if (hostConstructorCount != 1) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                element.getSimpleName() + " needs exactly one public one-argument constructor, "
                                        + "taking its host scene; it has " + hostConstructorCount,
                                element);
                        continue;
                    }
                    Deque<TypeMirror> hostSupertypes = new ArrayDeque<>(List.of(host));
                    while (hostBaseFqcn == null && !hostSupertypes.isEmpty()) {
                        TypeMirror type = hostSupertypes.pop();
                        Element supertype = types.asElement(type);
                        if (supertype.getSimpleName().contentEquals(hostSimpleName)) {
                            hostBaseFqcn = ((TypeElement) supertype).getQualifiedName().toString();
                        }
                        hostSupertypes.addAll(types.directSupertypes(type));
                    }
                    hostById.put(id, ((TypeElement) types.asElement(host)).getQualifiedName().toString());
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
            if (typeFqcn == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "No class annotated with "
                        + annotationClass.getSimpleName() + " has a supertype named " + typeSimpleName);
                return true;
            }
            String genClassName = this.getClass().getSimpleName() + GENERATED_CLASS_NAME_SEPARATOR + this.collectionName;
            if (hostSimpleName != null && hostBaseFqcn == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "No host of "
                        + annotationClass.getSimpleName() + " has a supertype named " + hostSimpleName);
                return true;
            }
            writeRegistryClass(genClassName, mainEntries,
                    partitionDesktopOnly ? desktopEntries.keySet() : null, hostSimpleName == null ? null : hostById);
            if (partitionDesktopOnly) {
                writeRegistryClass(genClassName + DESKTOP_SUFFIX, desktopEntries, null, null);
            }
            generated = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    /**
     * Emit one registry source file mapping id to constructor reference, or, for a hosted registry,
     * to a factory taking the host with a test of which hosts fit and the host type's name.
     *
     * @param genClassName simple name of the generated class
     * @param entries id-to-class entries the class's {@code MAP} holds
     * @param desktopOnlyIds ids emitted as {@code DESKTOP_ONLY_IDS}, or null to omit the set
     * @param hostById id-to-host-class entries for a hosted registry, or null for suppliers
     * @throws Exception if the source file cannot be written
     */
    private void writeRegistryClass(String genClassName, Map<String, String> entries,
            Set<String> desktopOnlyIds, Map<String, String> hostById) throws Exception {
        JavaFileObject file = processingEnv.getFiler()
                .createSourceFile(this.getClass().getPackageName() + "." + genClassName);
        try (Writer out = file.openWriter()) {
            out.write("package " + this.getClass().getPackageName() + ";\n\n");
            out.write("import java.util.*;\n");
            if (hostById != null) {
                out.write("import java.util.function.Function;\n");
                out.write("import java.util.function.Predicate;\n\n");
                out.write("public final class " + genClassName + " {\n");
                out.write("\tpublic static final Map<String, Function<" + hostBaseFqcn + ", ? extends "
                        + typeFqcn + ">> MAP = new TreeMap<>();\n\n");
                out.write("\tpublic static final Map<String, Predicate<" + hostBaseFqcn
                        + ">> HOSTED_BY = new TreeMap<>();\n\n");
                out.write("\tpublic static final Map<String, String> HOST_NAMES = new TreeMap<>();\n\n");
            } else {
                out.write("import " + typeFqcn + ";\n");
                out.write("import java.util.function.Supplier;\n\n");
                out.write("public final class " + genClassName + " {\n");
                out.write("\tpublic static final Map<String, Supplier<? extends " + typeFqcn
                        + ">> MAP = new HashMap<>();\n\n");
            }
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
                if (hostById == null) {
                    out.write("\t\tMAP.put(\"" + entry.getKey() + "\", " + entry.getValue() + "::new);\n");
                    continue;
                }
                String id = "\"" + entry.getKey() + "\"";
                String hostFqcn = hostById.get(entry.getKey());
                out.write("\t\tMAP.put(" + id + ", host -> new " + entry.getValue() + "((" + hostFqcn
                        + ") host));\n");
                out.write("\t\tHOSTED_BY.put(" + id + ", host -> host instanceof " + hostFqcn + ");\n");
                out.write("\t\tHOST_NAMES.put(" + id + ", \""
                        + hostFqcn.substring(hostFqcn.lastIndexOf('.') + 1) + "\");\n");
            }
            out.write("\t}\n");
            out.write("}\n");
        }
    }
}
