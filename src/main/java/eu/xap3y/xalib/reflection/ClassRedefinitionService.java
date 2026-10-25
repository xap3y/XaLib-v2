package eu.xap3y.xalib.reflection;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ClassRedefinitionService {

    private static final String AGENT_CLASS = "eu.xap3y.xalib.agent.XaLibAgent";
    private static final String AGENT_INSTRUCTIONS = "XaLib agent is not active. Start the server with "
            + "-javaagent:/path/to/XaLib-agent.jar before -jar (one server restart is required).";

    private final Path directory;

    public ClassRedefinitionService(Path dataDirectory) {
        directory = dataDirectory.toAbsolutePath().normalize().resolve("classes");
    }

    public ExportResult exportClass(String pluginName, Class<?> target, boolean overwrite) throws IOException {
        Path file = classFile(pluginName, target);
        Files.createDirectories(file.getParent());
        if (!overwrite && Files.exists(file)) {
            throw new IOException("Export already exists: " + file + ". Use export <Plugin> <Class> overwrite to replace it.");
        }
        byte[] bytes;
        Class<?> agent = findAgent();
        boolean liveBytecode = agent != null;
        if (liveBytecode) {
            try {
                bytes = (byte[]) agent.getMethod("readClassBytes", Class.class).invoke(null, target);
            } catch (ReflectiveOperationException exception) {
                throw new IOException("Cannot export live class: " + reflectionCause(exception), exception);
            }
        } else {
            String resource = "/" + target.getName().replace('.', '/') + ".class";
            try (InputStream input = target.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IOException("Cannot find class resource " + resource);
                }
                bytes = input.readAllBytes();
            }
        }
        if (overwrite) {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        }
        return new ExportResult(file, liveBytecode);
    }

    public Path redefineClass(String pluginName, Class<?> target) throws IOException, UnmodifiableClassException {
        Instrumentation instrumentation = requireInstrumentation();
        if (!instrumentation.isRedefineClassesSupported() || !instrumentation.isModifiableClass(target)) {
            throw new IllegalArgumentException("This JVM cannot redefine " + target.getName());
        }
        Path file = classFile(pluginName, target);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Class file not found: " + file + ". Export the class first.");
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            instrumentation.redefineClasses(new ClassDefinition(target, bytes));
        } catch (ClassNotFoundException exception) {
            throw new IllegalArgumentException("Target class could not be redefined", exception);
        } catch (UnsupportedOperationException exception) {
            throw new IllegalArgumentException("The JVM rejected the class change. On standard Java, change method bodies only; "
                    + "do not add/remove fields or methods, change signatures, or change inheritance. " + exception.getMessage(), exception);
        } catch (LinkageError exception) {
            throw new IllegalArgumentException("The JVM rejected the .class file: " + exception
                    + ". Compile the same fully qualified class name for the server's Java version.", exception);
        }
        return file;
    }

    private Path classFile(String pluginName, Class<?> target) {
        if (!pluginName.matches("[A-Za-z0-9_.-]+") || pluginName.equals(".") || pluginName.equals("..")) {
            throw new IllegalArgumentException("Invalid plugin name for export directory");
        }
        Path pluginDirectory = directory.resolve(pluginName);
        Path file = pluginDirectory.resolve(target.getName().replace('.', '/') + ".class").normalize();
        if (!file.startsWith(pluginDirectory)) {
            throw new IllegalArgumentException("Class file must stay inside the plugin export directory");
        }
        return file;
    }

    private Class<?> findAgent() {
        try {
            // Never load the bridge through the plugin classloader: it would have separate static state.
            Class<?> agent = Class.forName(AGENT_CLASS, false, ClassLoader.getSystemClassLoader());
            return agent.getMethod("getInstrumentation").invoke(null) == null ? null : agent;
        } catch (ClassNotFoundException exception) {
            return null;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot access the XaLib agent", exception);
        }
    }

    private Instrumentation requireInstrumentation() {
        Class<?> agent = findAgent();
        if (agent == null) {
            throw new IllegalStateException(AGENT_INSTRUCTIONS);
        }
        try {
            return (Instrumentation) agent.getMethod("getInstrumentation").invoke(null);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot access the XaLib agent", exception);
        }
    }

    private Throwable reflectionCause(ReflectiveOperationException exception) {
        if (exception instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return exception;
    }

    public record ExportResult(Path file, boolean liveBytecode) {
    }
}
