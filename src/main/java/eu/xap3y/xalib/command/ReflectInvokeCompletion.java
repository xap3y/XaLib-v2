package eu.xap3y.xalib.command;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Completes reflection syntax using class metadata without invoking plugin code. */
final class ReflectInvokeCompletion {

    private static final List<String> TYPES = List.of(
            "String=", "Boolean=", "Byte=", "Short=", "Integer=", "Long=",
            "Float=", "Double=", "Character=", "UUID=", "CommandSender=",
            "ConsoleSender=", "Player=", "OfflinePlayer="
    );
    private final JavaPlugin owner;
    private final Map<Plugin, List<String>> classCache = new WeakHashMap<>();

    ReflectInvokeCompletion(JavaPlugin owner) {
        this.owner = owner;
    }

    List<String> suggest(String[] args) {
        String input = args.length == 0 ? "" : args[args.length - 1];
        Set<String> suggestions = new LinkedHashSet<>();
        try {
            if (args.length <= 1) {
                suggestions.addAll(List.of("help", "new", "field", "export", "redefine"));
                completeTarget(input, false, suggestions);
            } else {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "export", "redefine" -> {
                        if (args.length == 2) {
                            for (Plugin plugin : plugins()) {
                                suggestions.add(plugin.getName());
                            }
                        } else if (args.length == 3) {
                            for (Plugin plugin : plugins()) {
                                if (plugin.getName().equalsIgnoreCase(args[1])) {
                                    suggestions.addAll(classes(plugin));
                                }
                            }
                        } else if (args.length == 4 && args[0].equalsIgnoreCase("export")) {
                            suggestions.add("overwrite");
                        }
                    }
                    case "field" -> {
                        if (args.length == 2) {
                            completeTarget(input, true, suggestions);
                        } else if (args.length == 3) {
                            suggestions.addAll(List.of(
                                    "view", "clear", "add", "remove", "get", "put", "removeKey", "set", "replace"
                            ));
                        } else {
                            completeArgument(input, suggestions);
                            suggestions.add("Call=");
                        }
                    }
                    case "new" -> {
                        if (args.length == 2) {
                            completeExpression(input, suggestions);
                        }
                    }
                    case "help" -> {
                        // Help has no arguments.
                    }
                    default -> completeArgument(input, suggestions);
                }
            }
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            // Partial input or unavailable optional classes should produce no completion error.
        }
        String prefix = input.toLowerCase(Locale.ROOT);
        return suggestions.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted()
                .limit(100)
                .toList();
    }

    private Plugin[] plugins() {
        return owner == null ? new Plugin[0] : owner.getServer().getPluginManager().getPlugins();
    }

    private void completeTarget(String input, boolean fields, Set<String> suggestions)
            throws ReflectiveOperationException {
        int hash = input.indexOf('#');
        if (hash < 0) {
            for (Plugin plugin : plugins()) {
                suggestions.addAll(classes(plugin));
            }
            return;
        }
        String prefix = input.substring(0, hash + 1);
        Class<?> type = resolveClass(input.substring(0, hash));
        if (!fields) {
            for (Method method : type.getMethods()) {
                suggestions.add(prefix + method.getName());
            }
            return;
        }
        String path = input.substring(hash + 1);
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            for (String segment : path.substring(0, dot).split("\\.")) {
                type = findField(type, segment).getType();
            }
            prefix += path.substring(0, dot + 1);
        }
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    suggestions.add(prefix + field.getName());
                }
            }
        }
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Continue with inherited fields without reading their values.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private void completeArgument(String input, Set<String> suggestions) {
        if (!input.contains("=")) {
            suggestions.addAll(TYPES);
            return;
        }
        String type = input.substring(0, input.indexOf('='));
        switch (type) {
            case "Boolean", "boolean" -> {
                suggestions.add(type + "=true");
                suggestions.add(type + "=false");
            }
            case "CommandSender" -> {
                suggestions.add("CommandSender=me");
                suggestions.add("CommandSender=console");
                completePlayers(type, suggestions);
            }
            case "ConsoleSender", "ConsoleCommandSender" -> {
                suggestions.add(type + "=console");
                suggestions.add(type + "=me");
            }
            case "Player", "OfflinePlayer", "UUID" -> completePlayers(type, suggestions);
            default -> {
                // Free-form values do not have useful generic suggestions.
            }
        }
    }

    private void completePlayers(String type, Set<String> suggestions) {
        if (owner == null) {
            return;
        }
        owner.getServer().getOnlinePlayers().forEach(player -> suggestions.add(
                type + "=" + (type.equals("UUID") ? player.getUniqueId() : player.getName())
        ));
    }

    private void completeExpression(String input, Set<String> suggestions) throws ClassNotFoundException {
        int open = input.indexOf('(');
        if (open < 0) {
            suggestions.addAll(List.of("ArrayList(", "LinkedList(", "HashSet(", "HashMap("));
            for (Plugin plugin : plugins()) {
                for (String name : classes(plugin)) {
                    suggestions.add(name + "(");
                }
            }
            return;
        }
        int close = input.lastIndexOf(')');
        int argumentStart = Math.max(input.lastIndexOf('('), input.lastIndexOf(',')) + 1;
        if (argumentStart > close) {
            Set<String> arguments = new LinkedHashSet<>();
            completeArgument(input.substring(argumentStart), arguments);
            for (String argument : arguments) {
                suggestions.add(input.substring(0, argumentStart) + argument);
            }
            return;
        }
        // For a completed constructor, suggest public methods without creating the object.
        if (close == input.indexOf(')') && input.substring(close + 1).startsWith(".")) {
            Class<?> type = resolveClass(input.substring(0, open));
            String prefix = input.substring(0, close + 1) + ".";
            for (Method method : type.getMethods()) {
                suggestions.add(prefix + method.getName() + "(");
            }
        }
    }

    private Class<?> resolveClass(String name) throws ClassNotFoundException {
        if (name.endsWith(".java")) {
            name = name.substring(0, name.length() - 5);
        } else if (name.endsWith(".kt")) {
            name = name.substring(0, name.length() - 3);
        }
        name = switch (name) {
            case "ArrayList", "LinkedList", "HashSet", "HashMap" -> "java.util." + name;
            default -> name;
        };
        for (Plugin plugin : plugins()) {
            try {
                return Class.forName(name, false, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException ignored) {
                // Try the next plugin classloader.
            }
        }
        return Class.forName(name, false, getClass().getClassLoader());
    }

    private List<String> classes(Plugin plugin) {
        return classCache.computeIfAbsent(plugin, this::indexClasses);
    }

    private List<String> indexClasses(Plugin plugin) {
        Set<String> names = new LinkedHashSet<>();
        names.add(plugin.getClass().getName());
        try {
            Path location = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isDirectory(location)) {
                try (Stream<Path> files = Files.walk(location)) {
                    files.filter(path -> path.toString().endsWith(".class"))
                            .forEach(path -> addClassName(location.relativize(path).toString(), names));
                }
            } else {
                try (JarFile jar = new JarFile(location.toFile())) {
                    jar.stream().forEach(entry -> addClassName(entry.getName(), names));
                }
            }
        } catch (IOException | URISyntaxException | SecurityException | NullPointerException ignored) {
            // The plugin main class remains available if its source cannot be indexed.
        }
        return new ArrayList<>(names);
    }

    private void addClassName(String entry, Set<String> names) {
        if (entry.endsWith(".class") && !entry.startsWith("META-INF/")
                && !entry.endsWith("module-info.class") && !entry.endsWith("package-info.class")) {
            names.add(entry.substring(0, entry.length() - 6).replace('/', '.').replace('\\', '.'));
        }
    }
}
