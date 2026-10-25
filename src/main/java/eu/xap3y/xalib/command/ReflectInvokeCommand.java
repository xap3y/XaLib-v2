package eu.xap3y.xalib.command;

import eu.xap3y.xalib.reflection.ClassRedefinitionService;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Administrative bridge for deliberately invoking methods exposed by other plugins.
 */
public final class ReflectInvokeCommand implements TabExecutor {

    private static final String USAGE = "Usage: /xainvoke <fully.qualified.Class#method> [arguments...] | /xainvoke help";

    private final JavaPlugin owner;
    private final ReflectInvokeCompletion completion;

    public ReflectInvokeCommand(JavaPlugin owner) {
        this.owner = owner;
        this.completion = new ReflectInvokeCompletion(owner);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canUse(sender)) {
            sender.sendMessage("§cYou must be OP or have permission * or xalib.command.xainvoke to use this command.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§c" + USAGE);
            return true;
        }

        try {
            if (args[0].equalsIgnoreCase("help")) {
                sendHelp(sender);
                return true;
            }
            if (args[0].equalsIgnoreCase("export") || args[0].equalsIgnoreCase("redefine")) {
                handleClassCommand(sender, args);
                return true;
            }
            if (args[0].equals("new")) {
                if (args.length != 2) throw new IllegalArgumentException("Usage: /xainvoke new <Class(args).method(args)...>");
                Object result = invokeChain(args[1], sender);
                sender.sendMessage("§aInvocation chain completed" + (result == null ? "." : "; returned: §f" + result));
                return true;
            }
            if (args[0].equals("field")) {
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: /xainvoke field <PluginClass#field.path> <view|clear|add|remove|get|put|removeKey|set|replace> [Type=value ...]");
                }
                Target target = parseTarget(args[1]);
                Class<?> type = loadPluginClass(target.className());
                LiveField field = resolveLiveField(type, target.methodName());
                FieldArguments fieldArguments = splitFieldArguments(Arrays.copyOfRange(args, 3, args.length));
                FieldOperationResult result = field.field().getType() == String[].class
                        ? operateOnStringArray(field, args[2], fieldArguments.values(), sender)
                        : operateOnField(field.value(), args[2], fieldArguments.values(), sender);
                Object chainedResult = invokeFieldCalls(result.value(), fieldArguments.calls(), sender);
                String suffix = fieldArguments.calls().isEmpty()
                        ? result.description()
                        : result.description() + (chainedResult == null ? "; calls completed" : "; calls returned: " + chainedResult);
                sender.sendMessage("§aField §f" + type.getName() + "#" + target.methodName() + "§a: §f" + suffix);
                return true;
            }

            Target target = parseTarget(args[0]);
            Class<?> type = loadPluginClass(target.className());
            String[] values = Arrays.copyOfRange(args, 1, args.length);
            MethodCall call = findCall(type, target.methodName(), values, sender);
            if (call == null) {
                sender.sendMessage("§cNo compatible public method " + target.methodName() + " with " + values.length + " argument(s) was found on " + type.getName() + ".");
                return true;
            }

            Object receiver = Modifier.isStatic(call.method().getModifiers()) ? null : findReceiver(type);
            if (receiver == null && !Modifier.isStatic(call.method().getModifiers())) {
                receiver = createReceiver(type);
            }
            if (receiver == null && !Modifier.isStatic(call.method().getModifiers())) {
                sender.sendMessage("§c" + type.getName() + " is not a loaded plugin main class and has no accessible no-argument constructor, so an instance could not be obtained. Static methods can still be called.");
                return true;
            }

            call.method().setAccessible(true);
            Object result = call.method().invoke(receiver, call.arguments());
            sender.sendMessage("§aInvoked §f" + type.getName() + "#" + call.method().getName() + "§a successfully" +
                    (call.method().getReturnType() == Void.TYPE ? "." : "; returned: §f" + String.valueOf(result)));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            sender.sendMessage("§cThe invoked method threw " + cause.getClass().getName() + ": " + safeMessage(cause));
            cause.printStackTrace();
        } catch (Throwable e) {
            sender.sendMessage("§cInvocation failed: " + e.getClass().getName() + ": " + safeMessage(e));
            e.printStackTrace();
        }
        return true;
    }

    static boolean canUse(CommandSender sender) {
        return sender instanceof ConsoleCommandSender
                || sender.isOp()
                || sender.hasPermission("*")
                || sender.hasPermission("xalib.command.xainvoke");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!canUse(sender)) {
            return List.of();
        }
        return completion.suggest(args);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6--- XaInvoke help (console, OP or authorized admin) ---");
        sender.sendMessage("§e/xainvoke <Class#method> [Type=value ...] §7- invoke a public method.");
        sender.sendMessage("§e/xainvoke new <Class(args).method(args)...> §7- create an object and invoke chained methods.");
        sender.sendMessage("§e/xainvoke export <Plugin> <fully.qualified.Class> [overwrite] §7- save a compiled .class under plugins/XaLib/classes/<Plugin>/.");
        sender.sendMessage("§e/xainvoke redefine <Plugin> <fully.qualified.Class> §7- apply the edited/recompiled .class from the same path.");
        sender.sendMessage("§7Redefine requires XaLib-agent.jar: start Java with -javaagent:/path/to/XaLib-agent.jar before -jar.");
        sender.sendMessage("§7Edit Java source in your project/decompiler, compile it, then replace the exported .class. Standard JVM: method body changes only.");
        sender.sendMessage("§7Redefine changes runtime code only; it does not update the plugin JAR or rerun constructors, static initialization, or onEnable.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> view|clear §7- inspect or clear a live Collection/Map.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> add|remove <Type=value ...> §7- change a live Collection.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> get|remove|removeKey <Type=value> §7- read/remove a Map entry.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> put <key Type=value> <value Type=value> §7- change a Map.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> view|get Integer=<index> §7- inspect a String[] (view takes no index).");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> set Integer=<index> String=<value> §7- change a String[] element; indices start at 0.");
        sender.sendMessage("§e/xainvoke field <PluginClass#field.path> replace [String=value ...] §7- replace a String[]; no values creates an empty array. Final fields cannot be replaced.");
        sender.sendMessage("§eAppend Call=method(Type=value,...) to field commands to invoke methods on their result; multiple Call= entries stack.");
        sender.sendMessage("§7Types: String, Boolean, Byte, Short, Integer, Long, Float, Double, Character, UUID, CommandSender, ConsoleSender, Player, OfflinePlayer.");
        sender.sendMessage("§7CommandSender=me uses the executing admin; CommandSender=console and ConsoleSender=console use the server console.");
        sender.sendMessage("§7Example: /xainvoke field eu.xap3y.xagui.XaGui#openMenus get Player=XAP3Y Call=close()");
    }

    private void handleClassCommand(CommandSender sender, String[] args) throws Exception {
        boolean export = args[0].equalsIgnoreCase("export");
        if (args.length != 3 && !(export && args.length == 4 && args[3].equalsIgnoreCase("overwrite"))) {
            throw new IllegalArgumentException("Usage: /xainvoke " + (export
                    ? "export <Plugin> <fully.qualified.Class> [overwrite]"
                    : "redefine <Plugin> <fully.qualified.Class>"));
        }
        Plugin plugin = owner.getServer().getPluginManager().getPlugin(args[1]);
        if (plugin == null) {
            throw new IllegalArgumentException("No loaded plugin named '" + args[1] + "' was found");
        }
        ClassLoader loader = plugin.getClass().getClassLoader();
        Class<?> target = Class.forName(args[2], false, loader);
        if (target.getClassLoader() != loader) {
            throw new IllegalArgumentException(target.getName() + " is not owned by plugin " + plugin.getName()
                    + "; select the plugin that defines this class");
        }
        ClassRedefinitionService service = new ClassRedefinitionService(owner.getDataFolder().toPath());
        if (export) {
            ClassRedefinitionService.ExportResult result = service.exportClass(plugin.getName(), target, args.length == 4);
            sender.sendMessage("§aExported §f" + target.getName() + "§a to §f" + result.file());
            sender.sendMessage(result.liveBytecode()
                    ? "§7Exported live JVM bytecode. Replace this file with the recompiled .class, then run redefine."
                    : "§eAgent is not active: exported the classloader resource, which may differ from live modified code. Redefine requires -javaagent:/path/to/XaLib-agent.jar at server startup.");
        } else {
            service.redefineClass(plugin.getName(), target);
            sender.sendMessage("§aRedefined §f" + target.getName() + "§a in plugin §f" + plugin.getName() + "§a. New method calls use the updated code.");
            sender.sendMessage("§7Existing field values are preserved. The plugin JAR is unchanged; this patch lasts until the class is unloaded/server restarts.");
        }
    }

    private Target parseTarget(String input) {
        int separator = input.lastIndexOf('#');
        if (separator <= 0 || separator == input.length() - 1) {
            throw new IllegalArgumentException(USAGE);
        }
        String className = input.substring(0, separator);
        // Kotlin source files are commonly referred to as Main.kt; the JVM class is Main.
        if (className.endsWith(".java")) className = className.substring(0, className.length() - 5);
        if (className.endsWith(".kt")) className = className.substring(0, className.length() - 3);
        return new Target(className, input.substring(separator + 1));
    }

    /**
     * Executes expressions such as {@code new com.example.Gui().build(Player=XAP3Y).open(Player=XAP3Y)}.
     * Collection constructors accept typed initial values, for example:
     * {@code new ArrayList(String=one,String=two).remove(String=one)}.
     * Map constructors accept alternating typed key/value arguments.
     */
    private Object invokeChain(String expression, CommandSender source) throws ReflectiveOperationException {
        int constructorOpen = expression.indexOf('(');
        if (constructorOpen <= 0) throw new IllegalArgumentException("Expected a constructor after 'new'");
        int constructorClose = matchingParenthesis(expression, constructorOpen);
        String className = normalizeClassName(expression.substring(0, constructorOpen));
        Class<?> type = loadPluginClass(className);
        Object current = invokeConstructor(type, parseChainArguments(expression.substring(constructorOpen + 1, constructorClose), source));

        int position = constructorClose + 1;
        while (position < expression.length()) {
            if (expression.charAt(position++) != '.') throw new IllegalArgumentException("Expected '.' before the next method call");
            int open = expression.indexOf('(', position);
            if (open < position) throw new IllegalArgumentException("Expected method arguments in parentheses");
            String methodName = expression.substring(position, open);
            int close = matchingParenthesis(expression, open);
            List<TypedArgument> arguments = parseChainArguments(expression.substring(open + 1, close), source);
            current = invokeTypedMethod(current, methodName, arguments);
            if (current == null && close + 1 < expression.length()) {
                throw new IllegalArgumentException("" + methodName + " returned void, so another method cannot be chained after it");
            }
            position = close + 1;
        }
        return current;
    }

    private String normalizeClassName(String className) {
        if (className.endsWith(".java")) return className.substring(0, className.length() - 5);
        if (className.endsWith(".kt")) return className.substring(0, className.length() - 3);
        return switch (className) {
            case "ArrayList" -> "java.util.ArrayList";
            case "LinkedList" -> "java.util.LinkedList";
            case "Set", "HashSet" -> "java.util.HashSet";
            case "LinkedHashSet" -> "java.util.LinkedHashSet";
            case "TreeSet" -> "java.util.TreeSet";
            case "Map", "HashMap" -> "java.util.HashMap";
            case "LinkedHashMap" -> "java.util.LinkedHashMap";
            default -> className;
        };
    }

    private int matchingParenthesis(String expression, int open) {
        int depth = 0;
        for (int i = open; i < expression.length(); i++) {
            if (expression.charAt(i) == '(') depth++;
            if (expression.charAt(i) == ')' && --depth == 0) return i;
        }
        throw new IllegalArgumentException("Unclosed parentheses in invocation expression");
    }

    private List<TypedArgument> parseChainArguments(String input, CommandSender source) {
        if (input.isEmpty()) return List.of();
        List<TypedArgument> arguments = parseTypedArguments(input.split(",", -1), source);
        if (arguments == null) throw new IllegalArgumentException("Chain arguments must use Type=value syntax");
        return arguments;
    }

    private Object invokeConstructor(Class<?> type, List<TypedArgument> arguments) throws ReflectiveOperationException {
        if (Collection.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) type.getDeclaredConstructor().newInstance();
            for (TypedArgument argument : arguments) {
                collection.add(argument.value());
            }
            return collection;
        }
        if (Map.class.isAssignableFrom(type)) {
            if (arguments.size() % 2 != 0) {
                throw new IllegalArgumentException("Map constructors require alternating key/value arguments");
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) type.getDeclaredConstructor().newInstance();
            for (int i = 0; i < arguments.size(); i += 2) {
                map.put(arguments.get(i).value(), arguments.get(i + 1).value());
            }
            return map;
        }
        for (Constructor<?> constructor : type.getConstructors()) {
            Object[] values = convertTyped(constructor.getParameterTypes(), arguments);
            if (values != null) return constructor.newInstance(values);
        }
        throw new NoSuchMethodException("No compatible public constructor found on " + type.getName());
    }

    private Object invokeTypedMethod(Object receiver, String methodName, List<TypedArgument> arguments) throws ReflectiveOperationException {
        for (Method method : receiver.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Object[] values = convertTyped(method.getParameterTypes(), arguments);
            if (values != null) return method.invoke(receiver, values);
        }
        throw new NoSuchMethodException("No compatible public method " + methodName + " found on " + receiver.getClass().getName());
    }

    /**
     * Reads a field from a loaded plugin instance, following nested private fields as needed.
     * For example, PluginMain#arenaManager.players resolves the real manager held by PluginMain.
     */
    private LiveField resolveLiveField(Class<?> rootType, String fieldPath) throws ReflectiveOperationException {
        String[] segments = fieldPath.split("\\.", -1);
        if (segments.length == 0 || Arrays.stream(segments).anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Field path must use names separated by dots");
        }

        Object current = null;
        Class<?> currentType = rootType;
        for (int i = 0; i < segments.length; i++) {
            Field field = findField(currentType, segments[i]);
            field.setAccessible(true);
            Object receiver = null;
            if (Modifier.isStatic(field.getModifiers())) {
                current = field.get(null);
            } else {
                receiver = i == 0 ? findReceiver(rootType) : current;
                if (receiver == null) {
                    throw new IllegalArgumentException("Cannot find the live instance for " + currentType.getName()
                            + ". Start from the plugin main class (for example PluginMain#manager." + segments[i] + ") or use a static field.");
                }
                current = field.get(receiver);
            }
            if (i == segments.length - 1) {
                return new LiveField(field, receiver, current);
            }
            if (current == null && i < segments.length - 1) {
                throw new IllegalArgumentException("Field " + segments[i] + " is null, so its nested field cannot be read");
            }
            currentType = current == null ? field.getType() : current.getClass();
        }
        throw new IllegalArgumentException("Field path must contain a field name");
    }

    private FieldOperationResult operateOnStringArray(LiveField field, String operation, String[] rawValues,
                                                     CommandSender source) throws IllegalAccessException {
        String normalizedOperation = operation.toLowerCase(java.util.Locale.ROOT);
        if (normalizedOperation.equals("replace")) {
            if (Modifier.isFinal(field.field().getModifiers())) {
                throw new IllegalArgumentException("Cannot replace a final String[] field; use set to change its elements");
            }
            List<TypedArgument> values = rawValues.length == 0 ? List.of() : parseTypedArguments(rawValues, source);
            if (values == null) {
                throw new IllegalArgumentException("replace requires String=value arguments");
            }
            String[] replacement = new String[values.size()];
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i).type() != String.class) {
                    throw new IllegalArgumentException("replace requires String=value arguments");
                }
                replacement[i] = (String) values.get(i).value();
            }
            field.field().set(field.receiver(), replacement);
            return new FieldOperationResult("replaced array; size=" + replacement.length + ", values=" + Arrays.toString(replacement), replacement);
        }

        String[] array = (String[]) field.value();
        if (array == null) {
            throw new IllegalArgumentException("The target String[] field is null; use replace to initialize it");
        }
        if (normalizedOperation.equals("view")) {
            requireNoValues(rawValues, "view");
            return new FieldOperationResult("size=" + array.length + ", values=" + Arrays.toString(array), array);
        }
        if (!normalizedOperation.equals("get") && !normalizedOperation.equals("set")) {
            throw new IllegalArgumentException("String[] operation must be view, get, set, or replace");
        }
        List<TypedArgument> values = parseTypedArguments(rawValues, source);
        int expectedValues = normalizedOperation.equals("set") ? 2 : 1;
        if (values == null || values.size() != expectedValues || values.getFirst().type() != Integer.class) {
            throw new IllegalArgumentException(normalizedOperation + " requires Integer=<index>"
                    + (expectedValues == 2 ? " String=<value>" : ""));
        }
        int index = (Integer) values.getFirst().value();
        if (index < 0 || index >= array.length) {
            throw new IllegalArgumentException("Array index " + index + " is out of bounds for length " + array.length + "; indices start at 0");
        }
        if (normalizedOperation.equals("get")) {
            return new FieldOperationResult("element at index " + index + " is " + array[index], array[index]);
        }
        if (values.get(1).type() != String.class) {
            throw new IllegalArgumentException("set requires Integer=<index> String=<value>");
        }
        array[index] = (String) values.get(1).value();
        return new FieldOperationResult("set index " + index + "; values=" + Arrays.toString(array), array);
    }

    private Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Keep walking the class hierarchy.
            }
        }
        throw new NoSuchFieldException("No field named '" + name + "' exists on " + type.getName());
    }

    private FieldArguments splitFieldArguments(String[] rawArguments) {
        List<String> values = new ArrayList<>();
        List<String> calls = new ArrayList<>();
        boolean foundCall = false;
        for (String argument : rawArguments) {
            if (argument.startsWith("Call=")) {
                foundCall = true;
                String call = argument.substring("Call=".length());
                if (call.isEmpty()) throw new IllegalArgumentException("Call= must include a method expression, such as Call=close()");
                calls.add(call);
            } else {
                if (foundCall) throw new IllegalArgumentException("Type=value arguments must come before Call= expressions");
                values.add(argument);
            }
        }
        return new FieldArguments(values.toArray(String[]::new), calls);
    }

    private Object invokeFieldCalls(Object current, List<String> calls, CommandSender source) throws ReflectiveOperationException {
        for (int i = 0; i < calls.size(); i++) {
            if (current == null) throw new IllegalArgumentException("Cannot invoke " + calls.get(i) + " because the previous result was null");
            String expression = calls.get(i);
            int open = expression.indexOf('(');
            if (open <= 0 || !expression.endsWith(")") || matchingParenthesis(expression, open) != expression.length() - 1) {
                throw new IllegalArgumentException("Call expressions must look like Call=method(Type=value,...)");
            }
            String methodName = expression.substring(0, open);
            List<TypedArgument> arguments = parseChainArguments(expression.substring(open + 1, expression.length() - 1), source);
            current = invokeTypedMethod(current, methodName, arguments);
            if (current == null && i < calls.size() - 1) {
                throw new IllegalArgumentException(methodName + " returned void, so another Call= cannot follow it");
            }
        }
        return current;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FieldOperationResult operateOnField(Object fieldValue, String operation, String[] rawValues, CommandSender source) {
        if (fieldValue == null) throw new IllegalArgumentException("The target field is null");
        String normalizedOperation = operation.toLowerCase(java.util.Locale.ROOT);
        if (normalizedOperation.equals("view")) {
            requireNoValues(rawValues, "view");
            if (fieldValue instanceof Collection<?> collection) return new FieldOperationResult("size=" + collection.size() + ", values=" + collection, fieldValue);
            if (fieldValue instanceof Map<?, ?> map) return new FieldOperationResult("size=" + map.size() + ", entries=" + map, fieldValue);
            return new FieldOperationResult(String.valueOf(fieldValue), fieldValue);
        }

        if (normalizedOperation.equals("clear")) {
            requireNoValues(rawValues, "clear");
            if (fieldValue instanceof Collection<?> collection) {
                int size = collection.size();
                collection.clear();
                return new FieldOperationResult("cleared " + size + " item(s)", fieldValue);
            }
            if (fieldValue instanceof Map<?, ?> map) {
                int size = map.size();
                map.clear();
                return new FieldOperationResult("cleared " + size + " entry/entries", fieldValue);
            }
            throw new IllegalArgumentException("clear requires a Collection or Map field, but found " + fieldValue.getClass().getName());
        }

        List<TypedArgument> values = parseTypedArguments(rawValues, source);
        if (values == null) throw new IllegalArgumentException("Field values must use Type=value syntax");
        if (fieldValue instanceof Collection collection) {
            return operateOnCollection(collection, normalizedOperation, values);
        }
        if (fieldValue instanceof Map map) {
            return operateOnMap(map, normalizedOperation, values);
        }
        throw new IllegalArgumentException(operation + " requires a Collection or Map field, but found " + fieldValue.getClass().getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FieldOperationResult operateOnCollection(Collection collection, String operation, List<TypedArgument> values) {
        return switch (operation) {
            case "add" -> {
                int added = 0;
                for (TypedArgument value : values) if (collection.add(value.value())) added++;
                yield new FieldOperationResult("added " + added + " item(s); size=" + collection.size(), collection);
            }
            case "remove" -> {
                int removed = 0;
                for (TypedArgument value : values) if (collection.remove(value.value())) removed++;
                yield new FieldOperationResult("removed " + removed + " item(s); size=" + collection.size(), collection);
            }
            default -> throw new IllegalArgumentException("Collection operation must be view, clear, add, or remove");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private FieldOperationResult operateOnMap(Map map, String operation, List<TypedArgument> values) {
        return switch (operation) {
            case "put" -> {
                if (values.size() % 2 != 0) throw new IllegalArgumentException("put requires alternating key/value arguments");
                for (int i = 0; i < values.size(); i += 2) map.put(values.get(i).value(), values.get(i + 1).value());
                yield new FieldOperationResult("stored " + (values.size() / 2) + " entr" + (values.size() == 2 ? "y" : "ies") + "; size=" + map.size(), map);
            }
            case "get" -> {
                if (values.size() != 1) throw new IllegalArgumentException("get requires exactly one key");
                Object key = resolveMapKey(map, values.getFirst().value());
                if (!map.containsKey(key)) {
                    yield new FieldOperationResult("no entry found for key " + key, null);
                }
                yield new FieldOperationResult("entry for " + key + " is " + map.get(key), map.get(key));
            }
            case "remove", "removekey" -> {
                int removed = 0;
                for (TypedArgument value : values) {
                    Object key = resolveMapKey(map, value.value());
                    if (map.containsKey(key)) {
                        map.remove(key);
                        removed++;
                    }
                }
                yield new FieldOperationResult("removed " + removed + " entry/entries; size=" + map.size(), map);
            }
            default -> throw new IllegalArgumentException("Map operation must be view, clear, get, put, remove, or removeKey");
        };
    }

    private Object resolveMapKey(Map<?, ?> map, Object suppliedKey) {
        if (map.containsKey(suppliedKey)) return suppliedKey;
        if (suppliedKey instanceof OfflinePlayer player && map.containsKey(player.getUniqueId())) return player.getUniqueId();
        return suppliedKey;
    }

    private void requireNoValues(String[] values, String operation) {
        if (values.length != 0) throw new IllegalArgumentException(operation + " does not accept values");
    }

    private Class<?> loadPluginClass(String className) throws ClassNotFoundException {
        List<Throwable> failures = new ArrayList<>();
        for (Plugin plugin : owner.getServer().getPluginManager().getPlugins()) {
            try {
                return Class.forName(className, true, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                failures.add(e);
            }
        }
        ClassNotFoundException failure = new ClassNotFoundException("Could not find " + className + " in any loaded plugin classloader");
        failures.forEach(failure::addSuppressed);
        throw failure;
    }

    private Object findReceiver(Class<?> type) {
        for (Plugin plugin : owner.getServer().getPluginManager().getPlugins()) {
            if (type.isInstance(plugin)) {
                return plugin;
            }
        }
        return null;
    }

    private Object createReceiver(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return null;
        }
    }

    private MethodCall findCall(Class<?> type, String methodName, String[] values, CommandSender source) {
        List<TypedArgument> typedArguments = parseTypedArguments(values, source);
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            Object[] converted = typedArguments == null
                    ? convertLegacy(method.getParameterTypes(), values, source)
                    : convertTyped(method.getParameterTypes(), typedArguments);
            if (converted != null) {
                return new MethodCall(method, converted);
            }
        }
        return null;
    }

    /** Backwards-compatible mode: values without a Type= prefix are inferred from the target method. */
    private Object[] convertLegacy(Class<?>[] types, String[] values, CommandSender source) {
        Object[] result = new Object[types.length];
        int valueIndex = 0;
        try {
            for (int i = 0; i < types.length; i++) {
                // Auto-inject the actual sender when its type matches the method parameter.
                if (CommandSender.class.isAssignableFrom(types[i])) {
                    if (types[i].isInstance(source)) {
                        result[i] = source;
                    } else if (ConsoleCommandSender.class.isAssignableFrom(types[i])) {
                        result[i] = consoleSender("console", source);
                    } else {
                        return null;
                    }
                } else {
                    if (valueIndex >= values.length) return null;
                    result[i] = convert(types[i], values[valueIndex++]);
                }
            }
            return valueIndex == values.length ? result : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private List<TypedArgument> parseTypedArguments(String[] values, CommandSender source) {
        // No supplied value retains the legacy sender auto-injection used by reload handlers.
        if (values.length == 0) return null;
        List<TypedArgument> result = new ArrayList<>();
        for (String value : values) {
            int separator = value.indexOf('=');
            if (separator <= 0) return null;
            result.add(parseTypedArgument(value.substring(0, separator), value.substring(separator + 1), source));
        }
        return result;
    }

    private TypedArgument parseTypedArgument(String typeName, String value, CommandSender source) {
        return switch (typeName) {
            case "String" -> new TypedArgument(String.class, value);
            case "Boolean", "boolean" -> new TypedArgument(Boolean.class, Boolean.valueOf(value));
            case "Byte", "byte" -> new TypedArgument(Byte.class, Byte.valueOf(value));
            case "Short", "short" -> new TypedArgument(Short.class, Short.valueOf(value));
            case "Integer", "int" -> new TypedArgument(Integer.class, Integer.valueOf(value));
            case "Long", "long" -> new TypedArgument(Long.class, Long.valueOf(value));
            case "Float", "float" -> new TypedArgument(Float.class, Float.valueOf(value));
            case "Double", "double" -> new TypedArgument(Double.class, Double.valueOf(value));
            case "Character", "char" -> new TypedArgument(Character.class, convert(char.class, value));
            case "UUID" -> new TypedArgument(UUID.class, UUID.fromString(value));
            case "CommandSender" -> new TypedArgument(CommandSender.class, commandSender(value, source));
            case "ConsoleSender", "ConsoleCommandSender" -> new TypedArgument(ConsoleCommandSender.class, consoleSender(value, source));
            case "Player" -> new TypedArgument(Player.class, onlinePlayer(value));
            case "OfflinePlayer" -> new TypedArgument(OfflinePlayer.class, offlinePlayer(value));
            default -> throw new IllegalArgumentException("Unsupported argument type '" + typeName + "'. Use String, Boolean, numbers, Character, CommandSender, ConsoleSender, Player, or OfflinePlayer.");
        };
    }

    private Object[] convertTyped(Class<?>[] types, List<TypedArgument> arguments) {
        if (types.length != arguments.size()) return null;
        Object[] result = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> expected = wrap(types[i]);
            TypedArgument argument = arguments.get(i);
            if (!expected.isAssignableFrom(argument.type())) return null;
            result[i] = argument.value();
        }
        return result;
    }

    private CommandSender commandSender(String value, CommandSender source) {
        if (value.equalsIgnoreCase("me")) {
            return source;
        }
        if (value.equalsIgnoreCase("console")) {
            return consoleSender(value, source);
        }
        return onlinePlayer(value);
    }

    private ConsoleCommandSender consoleSender(String value, CommandSender source) {
        if (!value.equalsIgnoreCase("me") && !value.equalsIgnoreCase("console")) {
            throw new IllegalArgumentException("ConsoleSender must be 'console' or 'me'");
        }
        if (source instanceof ConsoleCommandSender console) {
            return console;
        }
        return owner.getServer().getConsoleSender();
    }

    private Player onlinePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) throw new IllegalArgumentException("No online player named '" + name + "' was found");
        return player;
    }

    private OfflinePlayer offlinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer player = Bukkit.getOfflinePlayer(name);
        if (!player.hasPlayedBefore() && !player.isOnline()) {
            throw new IllegalArgumentException("No known player named '" + name + "' was found");
        }
        return player;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convert(Class<?> type, String value) {
        if (type == String.class || type == Object.class || type == CharSequence.class) return value;
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(value);
        if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
        if (type == short.class || type == Short.class) return Short.valueOf(value);
        if (type == int.class || type == Integer.class) return Integer.valueOf(value);
        if (type == long.class || type == Long.class) return Long.valueOf(value);
        if (type == float.class || type == Float.class) return Float.valueOf(value);
        if (type == double.class || type == Double.class) return Double.valueOf(value);
        if (type == char.class || type == Character.class) {
            if (value.length() != 1) throw new IllegalArgumentException("Expected one character");
            return value.charAt(0);
        }
        if (type.isEnum()) return Enum.valueOf((Class<? extends Enum>) type, value.toUpperCase(java.util.Locale.ROOT));
        throw new IllegalArgumentException("Unsupported parameter type " + type.getName());
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null ? "(no message)" : error.getMessage();
    }

    private record Target(String className, String methodName) { }
    private record LiveField(Field field, Object receiver, Object value) {
    }
    private record MethodCall(Method method, Object[] arguments) { }
    private record TypedArgument(Class<?> type, Object value) { }
    private record FieldArguments(String[] values, List<String> calls) { }
    private record FieldOperationResult(String description, Object value) { }
}
