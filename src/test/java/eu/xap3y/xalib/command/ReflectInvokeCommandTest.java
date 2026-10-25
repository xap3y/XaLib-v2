package eu.xap3y.xalib.command;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReflectInvokeCommandTest {

    private final ReflectInvokeCommand command = new ReflectInvokeCommand(null);

    @BeforeEach
    void resetFields() {
        Fields.manager = new Manager();
        Fields.staticValues = new String[]{"static"};
    }

    @Test
    void changesElementInExistingArrayAndReadsIt() throws Throwable {
        String[] original = Fields.manager.values;

        operateArray("manager.values", "set", "Integer=1", "String=changed");

        assertSame(original, Fields.manager.values);
        assertArrayEquals(new String[]{"one", "changed"}, original);
        Object result = operateArray("manager.values", "get", "Integer=1");
        assertEquals("changed", resultValue(result));
        Object view = operateArray("manager.values", "view");
        assertTrue(resultDescription(view).contains("[one, changed]"));
    }

    @Test
    void replacesNestedInheritedArrayWithDifferentLength() throws Throwable {
        String[] original = Fields.manager.values;

        Object result = operateArray("manager.values", "replace", "String=first", "String=", "String=third");

        assertNotSame(original, Fields.manager.values);
        assertArrayEquals(new String[]{"first", "", "third"}, Fields.manager.values);
        assertArrayEquals(new String[]{"one", "two"}, original);
        assertSame(Fields.manager.values, resultValue(result));
    }

    @Test
    void replacesStaticNullAndEmptyArrays() throws Throwable {
        operateArray("staticValues", "replace", "String=replaced");
        assertArrayEquals(new String[]{"replaced"}, Fields.staticValues);

        operateArray("manager.nullValues", "replace", "String=initialized");
        assertArrayEquals(new String[]{"initialized"}, Fields.manager.nullValues);

        operateArray("manager.values", "replace");
        assertArrayEquals(new String[0], Fields.manager.values);
    }

    @Test
    void rejectsInvalidInputsWithoutChangingArray() throws Throwable {
        String[] original = Fields.manager.values;

        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set", "Integer=-1", "String=bad"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set", "Integer=2", "String=bad"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set", "String=0", "String=bad"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set", "Integer=0", "Integer=1"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "set", "Integer=0"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "get", "Integer=0", "String=extra"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "replace", "String=first", "Integer=2"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "replace", "untyped"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.values", "view", "String=extra"));
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.nullValues", "set", "Integer=0", "String=bad"));

        assertSame(original, Fields.manager.values);
        assertArrayEquals(new String[]{"one", "two"}, original);
    }

    @Test
    void changesFinalArrayElementsButRejectsReplacement() throws Throwable {
        operateArray("manager.finalValues", "set", "Integer=0", "String=changed");
        assertArrayEquals(new String[]{"changed"}, Fields.manager.finalValues);
        assertThrows(IllegalArgumentException.class, () -> operateArray("manager.finalValues", "replace", "String=other"));
        assertArrayEquals(new String[]{"changed"}, Fields.manager.finalValues);
    }

    @Test
    void preservesCollectionAndMapOperationsAndResultCalls() throws Throwable {
        operateExisting("manager.list", "add", "String=first", "String=second");
        operateExisting("manager.list", "remove", "String=first");
        assertEquals(List.of("second"), Fields.manager.list);
        assertSame(Fields.manager.list, resultValue(operateExisting("manager.list", "view")));
        operateExisting("manager.list", "clear");
        assertTrue(Fields.manager.list.isEmpty());

        operateExisting("manager.map", "put", "String=key", "String=value");
        Object entry = operateExisting("manager.map", "get", "String=key");
        assertEquals("value", resultValue(entry));
        Method calls = ReflectInvokeCommand.class.getDeclaredMethod("invokeFieldCalls", Object.class, List.class, CommandSender.class);
        assertEquals("VALUE", invoke(calls, resultValue(entry), List.of("toUpperCase()"), null));
        operateExisting("manager.map", "removeKey", "String=key");
        assertTrue(Fields.manager.map.isEmpty());
        operateExisting("manager.map", "put", "String=key", "String=value");
        operateExisting("manager.map", "clear");
        assertTrue(Fields.manager.map.isEmpty());
    }

    @Test
    void allowsConsoleOpAndWildcardButDeniesOrdinaryPlayers() {
        List<String> messages = new ArrayList<>();
        for (CommandSender sender : List.of(
                sender(ConsoleCommandSender.class, false, "", messages),
                sender(Player.class, true, "", messages),
                sender(Player.class, false, "*", messages),
                sender(Player.class, false, "xalib.command.xainvoke", messages)
        )) {
            assertTrue(ReflectInvokeCommand.canUse(sender));
            command.onCommand(sender, null, "xainvoke", new String[]{"help"});
            assertTrue(messages.getFirst().contains("XaInvoke help"));
            assertTrue(command.onTabComplete(sender, null, "xainvoke", new String[]{"he"}).contains("help"));
            messages.clear();
        }
        CommandSender ordinary = sender(Player.class, false, "", messages);
        assertFalse(ReflectInvokeCommand.canUse(ordinary));
        command.onCommand(ordinary, null, "xainvoke", new String[]{"help"});
        assertTrue(messages.getFirst().contains("must be OP"));
        assertTrue(command.onTabComplete(ordinary, null, "xainvoke", new String[]{""}).isEmpty());
    }

    @Test
    void usesActualAdminForMeAndLegacySenderInjection() throws Throwable {
        CommandSender admin = sender(Player.class, true, "", new ArrayList<>());
        Method typed = ReflectInvokeCommand.class.getDeclaredMethod(
                "parseTypedArgument", String.class, String.class, CommandSender.class
        );
        Object argument = invoke(typed, "CommandSender", "me", admin);
        assertSame(admin, argument.getClass().getDeclaredMethod("value").invoke(argument));
        Method legacy = ReflectInvokeCommand.class.getDeclaredMethod(
                "convertLegacy", Class[].class, String[].class, CommandSender.class
        );
        Object[] values = (Object[]) invoke(
                legacy, new Class<?>[]{CommandSender.class, Player.class}, new String[0], admin
        );
        assertArrayEquals(new Object[]{admin, admin}, values);
    }

    @Test
    void completesFieldsMethodsExpressionsAndTypedValuesWithoutInitializingClasses() {
        ReflectInvokeCompletion completion = new ReflectInvokeCompletion(null);
        String className = LazyMetadata.class.getName();
        assertFalse(metadataInitialized);
        assertTrue(completion.suggest(new String[]{className + "#pi"}).contains(className + "#ping"));
        assertTrue(completion.suggest(new String[]{"field", className + "#val"}).contains(className + "#values"));
        assertTrue(completion.suggest(new String[]{"new", className + "().pi"}).contains(className + "().ping("));
        assertTrue(completion.suggest(new String[]{"new", className + "().ping(Boo"})
                .contains(className + "().ping(Boolean="));
        assertFalse(metadataInitialized);
        assertEquals(List.of("Boolean=false"), completion.suggest(new String[]{className + "#ping", "Boolean=f"}));
        assertTrue(completion.suggest(new String[]{"field", className + "#values", "rep"}).contains("replace"));
        assertEquals(List.of("overwrite"), completion.suggest(new String[]{"export", "Plugin", className, "ov"}));
        assertTrue(completion.suggest(new String[]{"field", "missing.Class#field", "get", "CommandSender=m"})
                .contains("CommandSender=me"));
        assertTrue(completion.suggest(new String[]{"missing.Class#"}).isEmpty());
    }

    private CommandSender sender(Class<? extends CommandSender> type, boolean op, String permission,
                                 List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "isOp" -> op;
                        case "hasPermission" -> args[0].equals(permission);
                        case "sendMessage" -> {
                            messages.add((String) args[0]);
                            yield null;
                        }
                        case "toString" -> "Test sender";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                }
        );
    }

    private static boolean metadataInitialized;

    public static class LazyMetadata {
        static {
            metadataInitialized = true;
        }

        private String[] values;

        public void ping() {
            throw new AssertionError("Completion must not invoke methods");
        }
    }

    private Object resolveField(String path) throws Throwable {
        Method resolve = ReflectInvokeCommand.class.getDeclaredMethod("resolveLiveField", Class.class, String.class);
        return invoke(resolve, Fields.class, path);
    }

    private Object operateArray(String path, String operation, String... values) throws Throwable {
        Object field = resolveField(path);
        Method operate = ReflectInvokeCommand.class.getDeclaredMethod("operateOnStringArray", field.getClass(), String.class, String[].class, CommandSender.class);
        return invoke(operate, field, operation, values, null);
    }

    private Object operateExisting(String path, String operation, String... values) throws Throwable {
        Object field = resolveField(path);
        Object value = field.getClass().getDeclaredMethod("value").invoke(field);
        Method operate = ReflectInvokeCommand.class.getDeclaredMethod("operateOnField", Object.class, String.class, String[].class, CommandSender.class);
        return invoke(operate, value, operation, values, null);
    }

    private Object resultValue(Object result) throws ReflectiveOperationException {
        return result.getClass().getDeclaredMethod("value").invoke(result);
    }

    private String resultDescription(Object result) throws ReflectiveOperationException {
        return (String) result.getClass().getDeclaredMethod("description").invoke(result);
    }

    private Object invoke(Method method, Object... arguments) throws Throwable {
        method.setAccessible(true);
        try {
            return method.invoke(command, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static class Fields {
        private static Manager manager;
        private static String[] staticValues;
    }

    private static class ParentManager {
        protected String[] values = {"one", "two"};
    }

    private static class Manager extends ParentManager {
        private String[] nullValues;
        private final String[] finalValues = {"final"};
        private final List<String> list = new ArrayList<>();
        private final Map<String, String> map = new HashMap<>();
    }
}
