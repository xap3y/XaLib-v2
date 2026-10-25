package eu.xap3y.xalib.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loaded by the system classloader, independently of Bukkit's plugin classloaders.
 */
public final class XaLibAgent {

    private static volatile Instrumentation instrumentation;

    private XaLibAgent() {
    }

    public static void premain(String arguments, Instrumentation instance) {
        instrumentation = instance;
    }

    public static void agentmain(String arguments, Instrumentation instance) {
        instrumentation = instance;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static synchronized byte[] readClassBytes(Class<?> target) throws UnmodifiableClassException {
        Instrumentation instance = instrumentation;
        if (instance == null || !instance.isRetransformClassesSupported() || !instance.isModifiableClass(target)) {
            throw new IllegalStateException("This JVM cannot export live bytecode for " + target.getName());
        }
        AtomicReference<byte[]> captured = new AtomicReference<>();
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined == target) {
                    captured.set(classfileBuffer.clone());
                }
                return null;
            }
        };
        instance.addTransformer(transformer, true);
        try {
            instance.retransformClasses(target);
        } finally {
            instance.removeTransformer(transformer);
        }
        byte[] bytes = captured.get();
        if (bytes == null) {
            throw new IllegalStateException("The JVM did not provide bytecode for " + target.getName());
        }
        return bytes;
    }
}
