package ixdar.annotations.meshnode;

import java.util.Set;

import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

import com.google.auto.service.AutoService;

import ixdar.annotations.RegistryProcessor;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes("ixdar.annotations.meshnode.MeshNodeAnnotation")
public class MeshNodeRegistry extends RegistryProcessor {

    public MeshNodeRegistry() {
        super(
                MeshNodeAnnotation.class,
                MeshNode.class,
                "MeshNodes");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        return super.process(annotations, roundEnv);
    }

}
