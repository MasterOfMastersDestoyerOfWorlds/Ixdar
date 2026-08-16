package ixdar.platform.teavm;

import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;

/**
 * Gives the browser build a {@code java.lang.Math.fma}, which TeaVM's classlib omits, by adding one
 * that delegates to {@link WebMath}.
 *
 * <p>Registered by name in the {@code web-teavm} profile's TeaVM plugin configuration.
 */
public class WebMathTransformer implements ClassHolderTransformer {

    /** The classlib type the browser build resolves {@code Math} calls against. */
    public static final String MATH_CLASS = "java.lang.Math";

    private static final String FMA = "fma";
    private static final String WEB_MATH_CLASS = "ixdar.platform.teavm.WebMath";

    /**
     * Add both {@code fma} overloads to {@code java.lang.Math}; every other class passes through.
     *
     * @param cls class being loaded into the TeaVM model
     * @param context supplies the class hierarchy the added programs are built against
     */
    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (!MATH_CLASS.equals(cls.getName())) {
            return;
        }
        addFma(cls, context, ValueType.DOUBLE);
        addFma(cls, context, ValueType.FLOAT);
    }

    /**
     * Add one {@code fma} overload whose body forwards to {@link WebMath}.
     *
     * @param cls the class being extended
     * @param context supplies the class hierarchy the program is built against
     * @param type parameter and return type of the overload
     */
    private static void addFma(ClassHolder cls, ClassHolderTransformerContext context, ValueType type) {
        MethodHolder method = new MethodHolder(FMA, type, type, type, type);
        method.setLevel(AccessLevel.PUBLIC);
        method.getModifiers().add(ElementModifier.STATIC);
        ProgramEmitter emitter = ProgramEmitter.create(method, context.getHierarchy());
        emitter.invoke(WEB_MATH_CLASS, FMA, type,
                emitter.var(1, type), emitter.var(2, type), emitter.var(3, type)).returnValue();
        cls.addMethod(method);
    }
}
