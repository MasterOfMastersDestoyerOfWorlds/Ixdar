package ixdar.annotations.geometry;

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
@SupportedAnnotationTypes("ixdar.annotations.geometry.GeometryAnnotation")
public class GeometryRegistry extends RegistryProcessor {

    /**
     * Wire the processor to discover {@link GeometryAnnotation}-tagged classes and emit
     * {@code GeometryRegistry_Geometries}, a map of geometry id to a {@link Geometry} supplier.
     */
    public GeometryRegistry() {
        super(
                GeometryAnnotation.class,
                Geometry.class,
                "Geometries");
    }

    /**
     * Delegate to {@link RegistryProcessor#process} to emit the generated geometries registry.
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
