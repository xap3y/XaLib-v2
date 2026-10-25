package eu.xap3y.xalib.reflection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ClassRedefinitionServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsClassResourceWithoutAgentAndRequiresExplicitOverwrite() throws Exception {
        ClassRedefinitionService service = new ClassRedefinitionService(temporaryDirectory);

        ClassRedefinitionService.ExportResult result = service.exportClass("Example", getClass(), false);

        assertFalse(result.liveBytecode());
        assertEquals(temporaryDirectory.resolve("classes/Example/eu/xap3y/xalib/reflection/ClassRedefinitionServiceTest.class"), result.file());
        byte[] original = Files.readAllBytes(result.file());
        assertEquals((byte) 0xCA, original[0]);
        Files.writeString(result.file(), "edited file");
        assertThrows(IOException.class, () -> service.exportClass("Example", getClass(), false));
        assertEquals("edited file", Files.readString(result.file()));

        service.exportClass("Example", getClass(), true);
        assertArrayEquals(original, Files.readAllBytes(result.file()));
    }

    @Test
    void rejectsDirectoryTraversalAndExplainsMissingAgent() {
        ClassRedefinitionService service = new ClassRedefinitionService(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> service.exportClass("../outside", getClass(), false));
        assertThrows(IllegalArgumentException.class, () -> service.exportClass("..", getClass(), false));
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.redefineClass("Example", getClass()));
        assertTrue(exception.getMessage().contains("-javaagent:"));
    }

    @Test
    void appliesRealHotSwapThroughAnIsolatedPluginClassloader() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = temporaryDirectory.resolve("probe.log");
        Process process = new ProcessBuilder(
                java.toString(),
                "-javaagent:" + System.getProperty("xalib.agentJar"),
                "-cp", System.getProperty("xalib.testClasspath"),
                ClassRedefinitionProbe.class.getName(),
                temporaryDirectory.toString(),
                System.getProperty("xalib.mainClasses")
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("HotSwap probe timed out: " + Files.readString(log));
        }
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("HOTSWAP_OK"), output);
    }
}
