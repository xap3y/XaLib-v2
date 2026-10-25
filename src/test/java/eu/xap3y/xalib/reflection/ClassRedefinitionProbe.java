package eu.xap3y.xalib.reflection;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs in a separate JVM with the packaged agent, using an isolated service classloader
 * to reproduce the system-agent / Bukkit-plugin classloader boundary.
 */
public final class ClassRedefinitionProbe {

    private ClassRedefinitionProbe() {
    }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        Path before = compile(root.resolve("before"), "LiveClass", "before:", "initial", false);
        Path after = compile(root.resolve("after"), "LiveClass", "after:", "new-initializer", false);
        Path incompatible = compile(root.resolve("incompatible"), "LiveClass", "invalid:", "initial", true);
        Path wrongName = compile(root.resolve("wrong"), "WrongClass", "wrong:", "initial", false);
        URL[] fixturePath = {before.toUri().toURL()};
        URL[] servicePath = {Path.of(arguments[1]).toUri().toURL()};

        try (URLClassLoader firstLoader = new URLClassLoader(fixturePath, null);
             URLClassLoader secondLoader = new URLClassLoader(fixturePath, null);
             URLClassLoader serviceLoader = new URLClassLoader(servicePath, ClassLoader.getPlatformClassLoader())) {
            Class<?> firstClass = firstLoader.loadClass("sample.LiveClass");
            Class<?> secondClass = secondLoader.loadClass("sample.LiveClass");
            Object existing = firstClass.getConstructor().newInstance();
            Object otherPlugin = secondClass.getConstructor().newInstance();
            firstClass.getField("state").set(existing, "preserved");
            firstClass.getField("prefix").set(null, "static-preserved");
            check("before:preserved:static-preserved".equals(value(existing)), "Initial fixture behavior");

            Class<?> serviceClass = serviceLoader.loadClass(ClassRedefinitionService.class.getName());
            Object service = serviceClass.getConstructor(Path.class).newInstance(root.resolve("plugins/XaLib"));
            Method export = serviceClass.getMethod("exportClass", String.class, Class.class, boolean.class);
            Method redefine = serviceClass.getMethod("redefineClass", String.class, Class.class);
            Object result = export.invoke(service, "Example", firstClass, false);
            check((Boolean) result.getClass().getMethod("liveBytecode").invoke(result), "Agent must export live bytes");
            Path exportedFile = (Path) result.getClass().getMethod("file").invoke(result);

            Files.copy(after.resolve("sample/LiveClass.class"), exportedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            redefine.invoke(service, "Example", firstClass);
            check("after:preserved:static-preserved".equals(value(existing)), "Existing instance and static state must survive");
            check("before:state:initial".equals(value(otherPlugin)), "Other plugin classloader must remain unchanged");

            export.invoke(service, "Example", firstClass, true);
            try (URLClassLoader exportedLoader = new URLClassLoader(new URL[]{exportedFile.getParent().getParent().toUri().toURL()}, null)) {
                Object reexported = exportedLoader.loadClass("sample.LiveClass").getConstructor().newInstance();
                check("after:state:new-initializer".equals(value(reexported)), "Re-export must contain redefined bytecode");
            }

            expectRejected(redefine, service, firstClass, exportedFile, incompatible.resolve("sample/LiveClass.class"));
            expectRejected(redefine, service, firstClass, exportedFile, wrongName.resolve("sample/WrongClass.class"));
            Files.writeString(exportedFile, "invalid class bytes");
            expectRejected(redefine, service, firstClass, exportedFile, null);
            check("after:preserved:static-preserved".equals(value(existing)), "Failed redefine must preserve working code");
        }
        System.out.println("HOTSWAP_OK");
    }

    private static Path compile(Path directory, String name, String bodyPrefix, String initialPrefix, boolean extraField) throws Exception {
        Files.createDirectories(directory);
        Path source = directory.resolve(name + ".java");
        String code = """
                package sample;

                public class %s {
                    public String state = "state";
                    public static String prefix = "%s";
                    %s

                    public String value() {
                        return "%s" + state + ":" + prefix;
                    }
                }
                """.formatted(name, initialPrefix, extraField ? "public int extra;" : "", bodyPrefix);
        Files.writeString(source, code);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", directory.toString(), source.toString());
        check(exit == 0, "Fixture compilation failed");
        return directory;
    }

    private static String value(Object instance) throws Exception {
        return (String) instance.getClass().getMethod("value").invoke(instance);
    }

    private static void expectRejected(Method redefine, Object service, Class<?> target, Path exportedFile, Path replacement) throws Exception {
        if (replacement != null) {
            Files.copy(replacement, exportedFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            redefine.invoke(service, "Example", target);
            throw new AssertionError("Invalid replacement was accepted");
        } catch (InvocationTargetException exception) {
            check(exception.getCause() instanceof IllegalArgumentException, "Expected an actionable replacement error: " + exception.getCause());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
