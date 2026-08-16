package ixdar.platform.teavm;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;

/**
 * Pins JOML's buffer accessor to its NIO implementation, dropping the only reference to
 * {@code sun.misc.Unsafe} — the branch JOML itself falls back to where Unsafe is unavailable.
 *
 * <p>Registered by name in the {@code web-teavm} profile's TeaVM plugin configuration.
 */
public class JomlUnsafeTransformer implements ClassHolderTransformer {

    /** JOML's buffer accessor, whose factory picks between an Unsafe and an NIO implementation. */
    public static final String MEM_UTIL_CLASS = "org.joml.MemUtil";

    /** The NIO accessor, JOML's own fallback when Unsafe is unavailable. */
    public static final String MEM_UTIL_NIO_CLASS = "org.joml.MemUtil$MemUtilNIO";

    private static final String CREATE_INSTANCE = "createInstance";

    /**
     * Replace {@code MemUtil.createInstance}; every other class passes through.
     *
     * @param cls class being loaded into the TeaVM model
     * @param context supplies the class hierarchy the replacement program is built against
     */
    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (!MEM_UTIL_CLASS.equals(cls.getName())) {
            return;
        }
        MethodHolder method = cls.getMethod(
                new MethodDescriptor(CREATE_INSTANCE, ValueType.object(MEM_UTIL_CLASS)));
        if (method == null) {
            return;
        }
        ProgramEmitter emitter = ProgramEmitter.create(method, context.getHierarchy());
        emitter.construct(MEM_UTIL_NIO_CLASS).returnValue();
    }
}
