package io.quarkus.agent.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillReaderWriteBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsDotDotWithoutOverwritingGlobalSibling() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("home/.quarkus/skills"));
        Path canary = root.getParent().resolve("SKILL.md");
        Files.writeString(canary, "sibling-canary");
        assertThrows(IllegalArgumentException.class, () -> writeGlobal("..", root, "replacement"));
        assertEquals("sibling-canary", Files.readString(canary));
    }

    @Test
    void rejectsDotWithoutWritingAtGlobalRoot() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("global"));
        assertThrows(IllegalArgumentException.class, () -> writeGlobal(".", root, "replacement"));
        assertFalse(Files.exists(root.resolve("SKILL.md")));
    }

    @Test
    void rejectsDotSegmentsInProjectScope() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        for (String name : new String[] { ".", ".." }) {
            assertThrows(IllegalArgumentException.class, () -> writeProject(name, project));
        }
        assertFalse(Files.exists(project.resolve(".agent")));
    }

    @Test
    void rejectsProjectSkillsDirectoryLinkToAnotherProject() throws Exception {
        Path projectA = Files.createDirectories(tempDir.resolve("project-A"));
        Path target = Files.createDirectories(tempDir.resolve("project-B/.agent/skills"));
        Path canary = target.resolve("existing/SKILL.md");
        Files.createDirectories(canary.getParent());
        Files.writeString(canary, "project-B-canary");
        Files.createDirectories(projectA.resolve(".agent"));
        directoryLink(projectA.resolve(".agent/skills"), target);

        assertThrows(IOException.class, () -> writeProject("new-skill", projectA));
        assertFalse(Files.exists(target.resolve("new-skill")));
        assertEquals("project-B-canary", Files.readString(canary));
    }

    @Test
    void rejectsAgentDirectoryLinkBeforeCreatingSkills() throws Exception {
        Path projectA = Files.createDirectories(tempDir.resolve("project-A"));
        Path projectB = Files.createDirectories(tempDir.resolve("project-B"));
        directoryLink(projectA.resolve(".agent"), projectB);

        assertThrows(IOException.class, () -> writeProject("new-skill", projectA));
        assertFalse(Files.exists(projectB.resolve("skills")));
    }

    @Test
    void rejectsLinkedProjectSkillDirectory() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path root = Files.createDirectories(project.resolve(".agent/skills"));
        Path target = Files.createDirectories(tempDir.resolve("other-project-skill"));
        directoryLink(root.resolve("linked-skill"), target);

        assertThrows(IOException.class, () -> writeProject("linked-skill", project));
        assertFalse(Files.exists(target.resolve("SKILL.md")));
    }

    @Test
    void rejectsLinkedGlobalSkillDirectory() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("global"));
        Path target = Files.createDirectories(tempDir.resolve("other-global-skill"));
        directoryLink(root.resolve("linked-skill"), target);

        assertThrows(IOException.class, () -> writeGlobal("linked-skill", root, "replacement"));
        assertFalse(Files.exists(target.resolve("SKILL.md")));
    }

    @Test
    void preservesOrdinaryGlobalUpdatesAndValidNames() throws Exception {
        Path root = tempDir.resolve("global");
        Path written = writeGlobal("quarkus.rest_v2-1", root, "first");
        assertTrue(Files.readString(written).contains("first"));
        assertEquals(written, writeGlobal("quarkus.rest_v2-1", root, "second"));
        assertTrue(Files.readString(written).contains("second"));
        assertFalse(Files.readString(written).contains("first"));
    }

    @Test
    void preservesCreateOnlyProjectWrites() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Path written = writeProject("quarkus-rest", project);
        String original = Files.readString(written);
        assertThrows(FileAlreadyExistsException.class, () -> writeProject("quarkus-rest", project));
        assertEquals(original, Files.readString(written));
    }

    @Test
    void acceptsExplicitlyConfiguredGlobalRootLink() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("selected-global-root"));
        Path alias = tempDir.resolve("global-root-alias");
        directoryLink(alias, target);

        writeGlobal("quarkus-rest", alias, "intended-root");
        assertTrue(Files.readString(target.resolve("quarkus-rest/SKILL.md")).contains("intended-root"));
    }

    @Test
    void acceptsExplicitProjectRootAlias() throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("selected-project"));
        Path alias = tempDir.resolve("project-root-alias");
        directoryLink(alias, project);

        writeProject("quarkus-rest", alias);
        assertTrue(Files.exists(project.resolve(".agent/skills/quarkus-rest/SKILL.md")));
    }

    private static Path writeGlobal(String name, Path root, String content) throws IOException {
        return SkillReader.writeSkill(name, content, null, null, SkillReader.SkillMode.OVERRIDE,
                null, root, false);
    }

    private static Path writeProject(String name, Path project) throws IOException {
        return SkillReader.writeSkill(name, "project-content", null, null, SkillReader.SkillMode.OVERRIDE,
                project.toString(), null, true, true);
    }

    private static void directoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Directory junctions work without Windows symbolic-link privileges.
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Timed out creating test junction");
                assertEquals(0, process.exitValue(),
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                process.destroyForcibly();
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
        assertEquals(target.toRealPath(), link.toRealPath(), "Directory link fixture must resolve to target");
    }
}
