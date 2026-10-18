package ixdar.annotations.tool;

import java.util.Set;

import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;

import com.google.auto.service.AutoService;

import ixdar.annotations.RegistryProcessor;

@AutoService(Processor.class)
public class ToolRegistry extends RegistryProcessor {

    /**
     * Wire the processor to discover {@link ToolAnnotation}-tagged classes and emit
     * {@code ToolRegistry_Tools}: per tool id, a factory building it for a host scene and a test
     * of which scenes host it. Both supertypes are named by simple name; they live in ixdar-app.
     */
    public ToolRegistry() {
        super(
                ToolAnnotation.class,
                "Tool",
                "Scene",
                "Tools");
    }

    /**
     * Delegate to {@link RegistryProcessor#process} to emit the generated tools registry.
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
