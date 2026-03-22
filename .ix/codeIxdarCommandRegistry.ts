import * as strings from "ixdar-vs/src/utils/strings";
export function makeTemplate(arg0: string) {
  return `package ixdar.annotations.command;

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
@SupportedAnnotationTypes("ixdar.annotations.command.${strings.toPascalCase(arg0)}")
public class CommandRegistry extends RegistryProcessor {

    public CommandRegistry() {
        super(
                ${strings.toPascalCase(arg0)}.class,
                TerminalOption.class,
                "Commands");
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnv) {
        return super.process(annotations, roundEnv);
    }

}`;
}
