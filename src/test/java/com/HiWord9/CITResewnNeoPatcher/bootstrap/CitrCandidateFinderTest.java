package com.HiWord9.CITResewnNeoPatcher.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitrCandidateFinderTest {
    @TempDir
    Path gameDir;

    // The patcher loaded in place: its own directory is the synced mod directory.
    @Test
    void findsCitrJarNextToThePatcherInTheSyncedDirectory() throws IOException {
        Path syncedDir = Files.createDirectories(gameDir.resolve("automodpack/client/active/mods"));
        Path syncedJar = jarWithId(syncedDir, "citresewn-1.2.2.jar", "citresewn");
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.of(syncedDir, modsDir), modsDir);

        assertEquals(1, scan.originalCandidates().size());
        assertEquals(syncedJar, scan.originalCandidates().getFirst().file().toPath());
    }

    // The fallback: the patcher loaded normally from mods/, the jar sits in the standard directory.
    @Test
    void fallsBackToStandardModsDirectory() throws IOException {
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));
        Path modsJar = jarWithId(modsDir, "citresewn-1.2.2.jar", "citresewn");
        Path emptyDir = Files.createDirectories(gameDir.resolve("empty"));

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.of(emptyDir, modsDir), modsDir);

        assertEquals(1, scan.originalCandidates().size());
        assertEquals(modsJar, scan.originalCandidates().getFirst().file().toPath());
    }

    @Test
    void ignoresUnrelatedFabricJars() throws IOException {
        Path syncedDir = Files.createDirectories(gameDir.resolve("automodpack/client/active/mods"));
        jarWithId(syncedDir, "unrelated-1.0.0.jar", "unrelated");
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.of(syncedDir, modsDir), modsDir);

        assertEquals(0, scan.originalCandidates().size());
        assertTrue(scan.patchedCandidates().isEmpty());
        assertTrue(scan.disabledCandidates().isEmpty());
    }

    @Test
    void classifiesPatchedAndDisabledVariantsFromModsDirectoryOnly() throws IOException {
        Path syncedDir = Files.createDirectories(gameDir.resolve("automodpack/client/active/mods"));
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));
        jarWithId(modsDir, "patched_citresewn-1.2.2.jar", "citresewn");
        jarWithId(modsDir, "citresewn-1.2.2.jar.disabled_by_citresewn_neopatcher", "citresewn");

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.of(syncedDir, modsDir), modsDir);

        assertEquals(0, scan.originalCandidates().size());
        assertEquals(1, scan.patchedCandidates().size());
        assertEquals(1, scan.disabledCandidates().size());
    }

    @Test
    void returnsEmptyScanWhenThereIsNoCitrJarAnywhere() throws IOException {
        Path modsDir = Files.createDirectories(gameDir.resolve("mods"));
        jarWithId(modsDir, "unrelated-1.0.0.jar", "unrelated");

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.of(modsDir), modsDir);

        assertEquals(0, scan.originalCandidates().size());
        assertEquals(0, scan.patchedCandidates().size());
        assertEquals(0, scan.disabledCandidates().size());
    }

    private static Path jarWithId(Path dir, String fileName, String id) throws IOException {
        Path jar = dir.resolve(fileName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write(fabricModJson(id).getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static String fabricModJson(String id) {
        return "{\"id\":\"" + id + "\",\"version\":\"1.2.2\"}";
    }
}
