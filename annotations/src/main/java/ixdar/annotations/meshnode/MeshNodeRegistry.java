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

    /**
     * Wire the processor to discover {@link MeshNodeAnnotation}-tagged classes and emit
     * {@code MeshNodeRegistry_MeshNodes}, a map of node id to a {@code MeshNode} supplier.
     * The supertype is named by FQN because the node SPI lives in ixdar-app.
     */
    public MeshNodeRegistry() {
        super(
                MeshNodeAnnotation.class,
                "ixdar.geometry.mesh.nodes.api.MeshNode",
                "MeshNodes",
                true);
    }

    /**
     * Delegate to {@link RegistryProcessor#process} to emit the generated mesh nodes registry.
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
