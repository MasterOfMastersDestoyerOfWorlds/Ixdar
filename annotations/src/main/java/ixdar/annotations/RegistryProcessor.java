package ixdar.annotations;

import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
    private static final String STR = "_";

    private boolean generated;
    private String fqcn;
    private Class<? extends Annotation> annotationClass;
    private Class<?> typeClass;
    private String collectionName;

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
        this.fqcn = this.getClass().getCanonicalName();
        this.annotationClass = annotationClass;
        this.typeClass = typeClass;
        this.collectionName = collectionName;
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
            String fqcn = this.fqcn + STR + this.collectionName;
            String genClassName = this.getClass().getSimpleName() + STR + this.collectionName;
            JavaFileObject file = processingEnv.getFiler().createSourceFile(fqcn);
            try (Writer out = file.openWriter()) {
                out.write("package " + this.getClass().getPackageName() + ";\n\n");
                out.write("import java.util.*;\n");
                out.write("import " + typeClass.getCanonicalName() + ";\n");
                out.write("import java.util.function.Supplier;\n\n");
                out.write("public final class " + genClassName + " {\n");
                out.write("\tpublic static final Map<String, Supplier<? extends " + typeClass.getName()
                        + ">> MAP = new HashMap<>();\n\n");
                out.write("\tstatic {\n");
                Map<String, String> seenIds = new HashMap<>();
                for (Element element : roundEnv.getElementsAnnotatedWith(annotationClass)) {
                    if (element.getKind() == ElementKind.CLASS) {
                        String fqClassName = ((TypeElement) element).getQualifiedName().toString();
                        String id = element.getSimpleName().toString();
                        String annotationName = annotationClass.getName();
                        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                            if (mirror.getAnnotationType().toString().equals(annotationName)) {
                                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : mirror
                                        .getElementValues().entrySet()) {
                                    String key = entry.getKey().getSimpleName().toString();
                                    if (key.equals("id")) {
                                        Object val = entry.getValue().getValue();
                                        if (val != null && !val.toString().isBlank()) {
                                            id = val.toString();
                                        }
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
                        out.write("\t\tMAP.put(\"" + id + "\", " + fqClassName + "::new);\n");
                    }
                }
                out.write("\t}\n");
                out.write("}\n");
            }
            generated = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }
}
