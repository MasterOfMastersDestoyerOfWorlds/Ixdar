package ixdar.annotations;

import java.io.Writer;
import java.lang.annotation.Annotation;
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
import javax.tools.JavaFileObject;

public abstract class RegistryProcessor extends AbstractProcessor {

    private boolean generated;
    private String fqcn;
    private Class<? extends Annotation> annotationClass;
    private Class<?> typeClass;
    private String collectionName;

    public RegistryProcessor(Class<? extends Annotation> annotationClass, Class<?> typeClass,
            String collectionName) {
        this.fqcn = this.getClass().getCanonicalName();
        this.annotationClass = annotationClass;
        this.typeClass = typeClass;
        this.collectionName = collectionName;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        if (generated) {
            return false;
        }
        try {
            String fqcn = this.fqcn + "_" + this.collectionName;
            String genClassName = this.getClass().getSimpleName() + "_" + this.collectionName;
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
                for (Element element : roundEnv.getElementsAnnotatedWith(annotationClass)) {
                    if (element.getKind() == ElementKind.CLASS) {
                        String fqClassName = ((TypeElement) element).getQualifiedName().toString();
                        String id = element.getSimpleName().toString();
                        String annotationName = annotationClass.getName();
                        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                            if (mirror.getAnnotationType().toString().equals(annotationName)) {
                                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
                                        mirror.getElementValues().entrySet()) {
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
