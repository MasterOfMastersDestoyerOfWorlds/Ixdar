package ixdar.annotations.scene;

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
@SupportedAnnotationTypes("ixdar.annotations.scene.SceneAnnotation")
public class SceneRegistry extends RegistryProcessor {

    /**
     * Wire the processor to discover {@link SceneAnnotation}-tagged classes and emit
     * {@code SceneRegistry_Scenes}, a map of scene id to a {@link SceneDrawable} supplier.
     */
    public SceneRegistry() {
        super(
                SceneAnnotation.class,
                SceneDrawable.class,
                "Scenes");
    }

    /**
     * Delegate to {@link RegistryProcessor#process} to emit the generated scenes registry.
     *
     * @param annotations annotation types requested for this round
     * @param roundEnv round environment supplying annotated elements
     * @return {@code true} to claim the supported annotations
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        return super.process(annotations, roundEnv);
    }

}
