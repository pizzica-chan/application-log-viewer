package com.example.aplv;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryTest {

    @Test
    void logFilePatterns() {
        assertTrue(Discovery.isLogFile("application.log"));
        assertTrue(Discovery.isLogFile("application.log.1"));
        assertTrue(Discovery.isLogFile("server.log"));
        assertTrue(Discovery.isLogFile("catalina.out"));
        assertFalse(Discovery.isLogFile("application.log.gz"));
        assertFalse(Discovery.isLogFile("readme.txt"));
    }

    @Test
    void globWildcards() {
        assertTrue(Discovery.globMatch("*.log", "application.log"));
        assertTrue(Discovery.globMatch("application*.log*", "application.log.2"));
        assertFalse(Discovery.globMatch("*.out", "application.log"));
    }

    @Test
    void findSkipsGitAndAplv(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve(".git").resolve("objects"));
        Files.createDirectories(tmp.resolve(".aplv"));
        Files.createDirectories(tmp.resolve("logs"));
        Files.write(tmp.resolve(".git/objects/fake.log"), "x".getBytes());
        Files.write(tmp.resolve(".aplv/index.db"), "x".getBytes());
        Files.write(tmp.resolve("logs/app.log"), "log".getBytes());

        List<Path> found = Discovery.findLogFiles(tmp);
        assertEquals(1, found.size());
        assertTrue(found.get(0).toString().endsWith("app.log"));
    }
}
