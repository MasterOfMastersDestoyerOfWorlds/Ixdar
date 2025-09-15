package shell.annotations;

import java.io.Writer;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;

import com.google.auto.service.AutoService;

@AutoService(Processor.class)
@SupportedAnnotationTypes("shell.annotations.SceneAnnotation")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class SceneRegistryProcessor extends AbstractProcessor {

    private boolean generated;

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        if (generated) {
            return false;
        }
        try {
            String fqcn = "shell.annotations.SceneRegistry_Scenes";
            JavaFileObject file = processingEnv.getFiler().createSourceFile(fqcn);
            try (Writer out = file.openWriter()) {
                out.write("package shell.annotations;\n\n");
                out.write("import java.util.*;\n");
                out.write("import shell.ui.Canvas3D;\n");
                out.write("import java.util.function.Supplier;\n\n");
                out.write("public final class SceneRegistry_Scenes {\n");
                out.write("\tpublic static final Map<String, Supplier<? extends Canvas3D>> MAP = new HashMap<>();\n\n");
                out.write("\tstatic {\n");
                for (Element element : roundEnv.getElementsAnnotatedWith(SceneAnnotation.class)) {
                    if (element.getKind() == ElementKind.CLASS) {
                        String fqClassName = ((TypeElement) element).getQualifiedName().toString();
                        String id = element.getAnnotation(SceneAnnotation.class).id();
                        out.write("\t\tMAP.put(\"" + id + "\", " + fqClassName + "::new);\n");
                    }
                }
                out.write("\t}\n");
                out.write("}\n");
            }
            generated = true;
        } catch (Exception e) {
            e.printStackTrace();
            float f = 1f / 0f;
        }
        return true;
    }

}
