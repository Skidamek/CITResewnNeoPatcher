package com.HiWord9.CITResewnNeoPatcher.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import net.neoforged.neoforgespi.locating.ModFileLoadingException;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class BadMixinRemover {
    public final static Path MODS_DIR_PATH = FMLPaths.MODSDIR.get();
    final static String MIXINS_CONFIG_FILE_NAME = "citresewn-defaults.mixins.json";
    final static Path TEMP_DIR = MODS_DIR_PATH.resolve("." + CITResewnNeoPatcherBootstrap.MODID);
    final static Path ORIGINAL_MIXINS_CONFIG_COPY = TEMP_DIR.resolve("original_" + MIXINS_CONFIG_FILE_NAME);
    final static List<String> BAD_MIXIN_CONFIG_LINES = new ArrayList<>(List.of( // modifiable
            "    \"types.armor.ArmorFeatureRendererMixin\"," // full line
    ));
    final static List<String> BAD_MIXIN_CLASSES = new ArrayList<>(List.of( // modifiable
            "shcm/shsupercm/fabric/citresewn/defaults/mixin/types/armor/ArmorFeatureRendererMixin.class"
    ));

    final static String NOTIF_MESSAGE = String.format("[%s] Partly patching CITR file", CITResewnNeoPatcherBootstrap.MODID);

    static Path currentCitrPath;
    static Path originalCitrPath;
    static Path currentPatchedPath;
    static Path finalDestPatchedPath;
    static JsonObject fabricConfigJson;
    // Set when the jar lives outside the mods directory (a synced mod directory of another loader):
    // such directories are not ours to rearrange, so the jar is patched in place and no extra
    // files are created next to it.
    static boolean inPlaceSourced;

    public static void load() throws Exception {
        StartupNotificationManager.addModMessage(NOTIF_MESSAGE);

        discoverCITR();

        if (inPlaceSourced) {
            patchJarInPlace();
        } else {
            prepareDirs();
            removeBadMixin();
        }
    }

    public static void cleanUp() throws Exception {
        // Null also when this launch loaded a previously patched jar without preparing anything:
        // then there is no patched copy or disabled original to restore either.
        if (finalDestPatchedPath == null) return;
        Files.delete(finalDestPatchedPath);
        Files.move(currentCitrPath, originalCitrPath);
    }

    private static void discoverCITR() {
        // The patcher jar's own directory is searched first: when another loader synced the pack,
        // that is its synced directory, and the CITResewn jar lives right next to the patcher.
        LinkedHashSet<Path> searchDirs = new LinkedHashSet<>();
        searchDirs.add(selfJarPath().getParent());
        searchDirs.add(MODS_DIR_PATH);

        CitrCandidateFinder.Scan scan = CitrCandidateFinder.find(List.copyOf(searchDirs), MODS_DIR_PATH);
        List<CitrCandidateFinder.CITRCandidate> originalCandidates = scan.originalCandidates();
        List<CitrCandidateFinder.CITRCandidate> patchedCandidates = scan.patchedCandidates();
        List<CitrCandidateFinder.CITRCandidate> disabledCandidates = scan.disabledCandidates();

        // fixing possible problems most of which can appear on previously terminated load

        boolean goodEnding = false;

        if (originalCandidates.size() > 1) {
            CITResewnNeoPatcherBootstrap.LOGGER.error("Only one CITResewn Jar have to be in mods folder or any additional mod location");
            throw new ModFileLoadingException("Multiple CITResewn Jars found");
        } else if (originalCandidates.isEmpty()) { // original empty
            CITResewnNeoPatcherBootstrap.LOGGER.warn("No CITResewn Jar found, trying to repair previous load if it's possible");
            if (disabledCandidates.size() > 1) {
                CITResewnNeoPatcherBootstrap.LOGGER.error("If no CITResewn Jar is located in mods folder, than only one Disabled CITResewn Jar have to be there");
                throw new ModFileLoadingException("Multiple disabled CITResewn Jar found");
            } else if (disabledCandidates.isEmpty()) {
                if (patchedCandidates.size() == 1) {
                    CITResewnNeoPatcherBootstrap.LOGGER.warn("No other CITResewn Jars found except of only one previously patched, trying to load it");

                    CitrCandidateFinder.CITRCandidate patchedCandidate = patchedCandidates.getFirst();
                    String version = getVersion(patchedCandidate.fabricModJson());
                    if (!isCompatible(version)) {
                        CITResewnNeoPatcherBootstrap.LOGGER.warn(
                                "Previously patched CITResewn Jar version is not compatible, expected {}, but found {}",
                                CITResewnNeoPatcherBootstrap.CITR_VERSION_RANGE, version
                        );
                        throw new ModFileLoadingException("Incompatible patched CITResewn version");
                    }

                    goodEnding = true;
                    CITResewnNeoPatcherBootstrap.LOGGER.warn("Loading previously patched CITResewn Jar");
                } else if (patchedCandidates.size() > 1) {
                    goodEnding = true;
                    CITResewnNeoPatcherBootstrap.LOGGER.warn("No other CITResewn Jars found except of multiple previously patched ones, this will likely not load correctly but not throwing here");
                } // else if empty throwing at the end
            } else {
                goodEnding = true;
                CITResewnNeoPatcherBootstrap.LOGGER.info("Found previously disabled CITResewn Jar, trying to repair");

                patchedCandidates.forEach(candidate -> candidate.file().delete());

                CitrCandidateFinder.CITRCandidate disabledCandidate = disabledCandidates.getFirst();
                File enabledFile = new File(
                        disabledCandidate.file().toPath().toString().replaceFirst(
                                (CitrCandidateFinder.DISABLED_SUFFIX.replace(".", "\\.")) + "$",
                                ""
                        )
                );
                disabledCandidate.file().renameTo(enabledFile);

                populateFields(new CitrCandidateFinder.CITRCandidate(enabledFile, disabledCandidate.fabricModJson()));
                CITResewnNeoPatcherBootstrap.LOGGER.info("Successfully loaded repaired disabled CITResewn Jar");
            }
        } else {
            CitrCandidateFinder.CITRCandidate originalCandidate = originalCandidates.getFirst();
            String version = getVersion(originalCandidate.fabricModJson());
            if (!isCompatible(version)) {
                CITResewnNeoPatcherBootstrap.LOGGER.error(
                        "CITResewnNeoPatcher requires CITResewn version range {}, but found {}",
                        CITResewnNeoPatcherBootstrap.CITR_VERSION_RANGE, version
                );
                throw new ModFileLoadingException("Incompatible CITResewn version");
            }

            goodEnding = true;
            populateFields(originalCandidate);

            patchedCandidates.forEach(candidate -> candidate.file().delete());
            disabledCandidates.forEach(candidate -> candidate.file().delete());
        }

        if (!goodEnding) throw new ModFileLoadingException("Could not find any CITResewn Jar file in Mods dir");
    }

    private static void populateFields(CitrCandidateFinder.CITRCandidate candidate) {
        fabricConfigJson = candidate.fabricModJson();
        originalCitrPath = candidate.file().toPath();
        currentCitrPath = originalCitrPath;
        inPlaceSourced = !MODS_DIR_PATH.equals(originalCitrPath.getParent());
        if (inPlaceSourced) return;
        currentPatchedPath = TEMP_DIR.resolve(CitrCandidateFinder.PATCHED_PREFIX + currentCitrPath.getFileName());
        finalDestPatchedPath = MODS_DIR_PATH.resolve(currentPatchedPath.getFileName());
    }

    private static void prepareDirs() throws IOException {
        Files.createDirectories(TEMP_DIR);
    }

    private static void removeBadMixin() throws Exception {
        Files.copy(currentCitrPath, currentPatchedPath, StandardCopyOption.REPLACE_EXISTING);

        removeBadMixinsFromJar(currentPatchedPath);

        currentCitrPath.toFile().renameTo(
                new File((currentCitrPath = Path.of(currentCitrPath + CitrCandidateFinder.DISABLED_SUFFIX)).toString())
        );

        Files.copy(currentPatchedPath, finalDestPatchedPath);

        currentPatchedPath.toFile().delete();
        TEMP_DIR.toFile().delete();
    }

    // Locates the patcher jar itself. When a loader loads it through a virtual file system, the
    // physical file path is resolved back out of that file system.
    private static Path selfJarPath() {
        try {
            CodeSource codeSource = BadMixinRemover.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new IllegalStateException("CodeSource is null for " + CITResewnNeoPatcherBootstrap.MODID);
            }

            return resolvePhysicalPath(Path.of(codeSource.getLocation().toURI()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to determine the " + CITResewnNeoPatcherBootstrap.MODID + " jar path", e);
        }
    }

    // Reflectively extracts the physical file path from a virtual (Union)FileSystem (e.g. Neo/Forge).
    private static Path resolvePhysicalPath(Path path) {
        try {
            Method getPrimaryPath = path.getFileSystem().getClass().getMethod("getPrimaryPath");
            if (getPrimaryPath.invoke(path.getFileSystem()) instanceof Path physical) return physical;
        } catch (NoSuchMethodException ignored) {
            // not a virtual file system, the plain path is the physical one
        } catch (Exception e) {
            CITResewnNeoPatcherBootstrap.LOGGER.error("Failed to resolve the physical path for {}", path, e);
        }
        return path;
    }

    // A jar from another loader's mod directory is not ours to rearrange: renaming it or adding a
    // patched copy next to it would corrupt that loader's state. Instead the bad mixin is removed
    // from the jar itself. The patch is idempotent, and when the loader resyncs the jar it reverts
    // to the unpatched original, after which the next launch patches it again.
    private static void patchJarInPlace() throws Exception {
        if (isAlreadyPatched(currentCitrPath)) {
            CITResewnNeoPatcherBootstrap.LOGGER.info("CITResewn jar is already patched in place");
            return;
        }

        Files.createDirectories(TEMP_DIR);

        // the temp file must not end in .jar: a copy left behind by a killed launch would
        // otherwise be scannable as a mod file by the loader that owns this directory
        Path tempPatched = Files.createTempFile(originalCitrPath.getParent(), "." + CITResewnNeoPatcherBootstrap.MODID + "-", ".tmp");
        try {
            Files.copy(originalCitrPath, tempPatched, StandardCopyOption.REPLACE_EXISTING);
            removeBadMixinsFromJar(tempPatched);
            Files.move(tempPatched, originalCitrPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            CITResewnNeoPatcherBootstrap.LOGGER.info("Patched the CITResewn jar in place: {}", originalCitrPath);
        } catch (Exception e) {
            Files.deleteIfExists(tempPatched);
            throw e;
        } finally {
            TEMP_DIR.toFile().delete();
        }
    }

    // Opens the jar and removes the bad mixin config entry and mixin class from the nested defaults jar.
    private static void removeBadMixinsFromJar(Path jarPath) throws Exception {
        FileSystem patchedJar = FileSystems.newFileSystem(jarPath);

        try {
            String defaultsJarDest = getDefaultsJarDest(fabricConfigJson);

            assert defaultsJarDest != null;
            Path defaultsJarPath = patchedJar.getPath(defaultsJarDest);

            FileSystem defaultsJar = FileSystems.newFileSystem(defaultsJarPath);

            try {
                Path mixinsConfigPath = defaultsJar.getPath(MIXINS_CONFIG_FILE_NAME);
                if (!Files.exists(mixinsConfigPath)) throw new NoSuchFileException(MIXINS_CONFIG_FILE_NAME);

                patchMixinsConfig(mixinsConfigPath);
                deleteBadMixins(defaultsJar);
            } finally {
                defaultsJar.close();
            }
        } finally {
            patchedJar.close();
        }
    }

    // True when the bad mixin class is already removed from the jar's nested defaults jar.
    private static boolean isAlreadyPatched(Path citrJar) throws IOException {
        FileSystem jar = FileSystems.newFileSystem(citrJar);

        try {
            String defaultsJarDest = getDefaultsJarDest(fabricConfigJson);
            assert defaultsJarDest != null;

            Path defaultsJarPath = jar.getPath(defaultsJarDest);
            FileSystem defaultsJar = FileSystems.newFileSystem(defaultsJarPath);

            try {
                for (String badMixinClass : BAD_MIXIN_CLASSES) {
                    if (Files.exists(defaultsJar.getPath(badMixinClass))) return false;
                }
            } finally {
                defaultsJar.close();
            }
        } finally {
            jar.close();
        }

        return true;
    }

    private static void deleteBadMixins(FileSystem defaultsJar) throws IOException {
        for (String badMixinClass : BAD_MIXIN_CLASSES) {
            Path badMixinClassPath = defaultsJar.getPath(badMixinClass);
            if (!Files.exists(badMixinClassPath)) throw new NoSuchFileException(badMixinClass);

            Files.delete(badMixinClassPath);
        }
    }

    private static void patchMixinsConfig(Path mixinsConfigPath) throws IOException {
        Files.copy(mixinsConfigPath, ORIGINAL_MIXINS_CONFIG_COPY, StandardCopyOption.REPLACE_EXISTING);

        try (
                BufferedReader br = Files.newBufferedReader(ORIGINAL_MIXINS_CONFIG_COPY, StandardCharsets.UTF_8);
                BufferedWriter bw = Files.newBufferedWriter(mixinsConfigPath, StandardCharsets.UTF_8)
        ) {
            String line;
            while ((line = br.readLine()) != null) {
                if (BAD_MIXIN_CONFIG_LINES.contains(line)) continue;
                bw.write(line);
                bw.newLine();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            ORIGINAL_MIXINS_CONFIG_COPY.toFile().delete();
        }
    }

    private static @Nullable String getDefaultsJarDest(JsonObject jsonObject) {
        String defaultsJarDest = null;
        for (JsonElement jarElement : jsonObject.getAsJsonArray("jars").asList()) {
            defaultsJarDest = jarElement.getAsJsonObject().get("file").getAsString();
            break;
        }
        return defaultsJarDest;
    }

    private static @Nullable String getVersion(JsonObject fabricModJson) {
        JsonElement versionElement = fabricModJson.get("version");
        if (versionElement == null) return null;

        return versionElement.getAsString().replaceAll("[+].*$", "");
    }

    private static boolean isCompatible(String version) {
        if (version == null) return false;
        return CITResewnNeoPatcherBootstrap.CITR_VERSION_RANGE.containsVersion(new DefaultArtifactVersion(version));
    }

}
