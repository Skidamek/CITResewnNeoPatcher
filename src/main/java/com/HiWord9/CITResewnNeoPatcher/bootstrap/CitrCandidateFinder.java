package com.HiWord9.CITResewnNeoPatcher.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Classifies CITResewn jars for the patcher.
 *
 * <p>
 * The search covers the directories the patcher itself can have been loaded from: the standard
 * mods directory, and the parent directory of the patcher jar. When a loader loads the patcher
 * from its own synced directory, that directory holds the CITResewn jar too. A jar found in the
 * standard mods directory goes through the legacy rename flow. A jar found anywhere else is
 * patched in place, because that directory belongs to whichever loader synced it. The patched and
 * disabled variants are artifacts of this patcher's own earlier runs and only ever exist in the
 * standard mods directory.
 * </p>
 *
 * <p>
 * Kept free of FML-bound statics so it stays unit-testable.
 * </p>
 */
final class CitrCandidateFinder {
    static final String FABRIC_MOD_JSON = "fabric.mod.json";
    static final String PATCHED_PREFIX = "patched_";
    static final String DISABLED_SUFFIX = ".disabled_by_" + CITResewnNeoPatcherBootstrap.MODID;
    static final String JAR_SUFFIX = ".jar";
    static final String CITR_ID = "citresewn";

    record CITRCandidate(File file, JsonObject fabricModJson) {}

    record Scan(List<CITRCandidate> originalCandidates,
                List<CITRCandidate> patchedCandidates,
                List<CITRCandidate> disabledCandidates) {}

    private CitrCandidateFinder() {}

    static boolean shouldReadModFile(String fileName) {
        return fileName.endsWith(JAR_SUFFIX) || fileName.endsWith(DISABLED_SUFFIX);
    }

    static Scan find(List<Path> searchDirs, Path modsDir) {
        List<CITRCandidate> originalCandidates = new ArrayList<>();
        List<CITRCandidate> patchedCandidates = new ArrayList<>();
        List<CITRCandidate> disabledCandidates = new ArrayList<>();

        for (Path dir : searchDirs) {
            File[] files = dir.toFile().listFiles();
            if (files == null) continue;

            for (File modFile : files) {
                if (modFile.isDirectory()) continue;
                String fileName = modFile.getName();
                if (!shouldReadModFile(fileName)) continue;

                CITRCandidate candidate = candidate(modFile);
                if (candidate == null) continue;

                if (fileName.startsWith(PATCHED_PREFIX)) {
                    // patched copies only ever belong to the mods directory flow; anywhere else
                    // they would be a foreign file in another loader's directory
                    if (!dir.equals(modsDir)) continue;
                    patchedCandidates.add(candidate);
                } else if (fileName.endsWith(DISABLED_SUFFIX)) {
                    if (!dir.equals(modsDir)) continue;
                    disabledCandidates.add(candidate);
                } else {
                    originalCandidates.add(candidate);
                }
            }
        }

        return new Scan(originalCandidates, patchedCandidates, disabledCandidates);
    }

    // Reads a mod file's fabric.mod.json and returns it only if the file is the CITResewn mod, null otherwise.
    static @Nullable JsonObject citrFabricConfig(File modFile) {
        JsonObject config = fabricModJson(modFile);
        if (config == null || !CITR_ID.equals(id(config))) return null;
        return config;
    }

    private static @Nullable CITRCandidate candidate(File modFile) {
        JsonObject config = citrFabricConfig(modFile);
        return config == null ? null : new CITRCandidate(modFile, config);
    }

    private static @Nullable JsonObject fabricModJson(File modFile) {
        try {
            return readJsonInsideJar(modFile.toPath(), FABRIC_MOD_JSON);
        } catch (Exception e) {
            CITResewnNeoPatcherBootstrap.LOGGER.error("Error while reading fabric.mod.json from {}, ignoring", modFile.getName(), e);
            return null;
        }
    }

    private static JsonObject readJsonInsideJar(Path jarPath, String entry) throws IOException {
        try (FileSystem jar = FileSystems.newFileSystem(jarPath)) {
            Path jsonPath = jar.getPath(entry);
            if (!Files.exists(jsonPath)) return null;

            try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
                return new Gson().fromJson(reader, JsonObject.class);
            }
        }
    }

    private static @Nullable String id(JsonObject fabricModJson) {
        JsonElement idElement = fabricModJson.get("id");
        return idElement == null ? null : idElement.getAsString();
    }
}
