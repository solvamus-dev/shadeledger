package org.shadeledger.matcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Proves the core ShadeLedger claim for the relocation case: given the
 * original input JAR, the relocation rule from the shade-plugin config,
 * and the shaded output JAR, verify (not guess) which output class files
 * are byte-for-byte the input classes under that known transformation.
 *
 * Two modes:
 *   --mode relocation (default) - the dependency's classes were moved to a
 *       new package by the shade plugin. The rule is applied to the original
 *       bytes ourselves and compared against the actual output.
 *   --mode identity - the dependency was merged in unchanged (plain merge,
 *       or merged with some classes filtered out). Classes are compared at
 *       the same path with no transformation.
 *
 * Usage:
 *   matcher --mode relocation \
 *           --original path/to/commons-text-1.9.jar \
 *           --output path/to/demo-relocation.jar \
 *           --pattern org.apache.commons.text \
 *           --shaded-pattern com.acme.shaded.commons.text \
 *           --group org.apache.commons --artifact commons-text --version 1.9 \
 *           --report path/to/report.json
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String mode = opts.getOrDefault("mode", "relocation");
        if (mode.equals("relocation")) {
            require(opts, "original", "output", "pattern", "shaded-pattern", "group", "artifact", "version", "report");
        } else {
            require(opts, "original", "output", "pattern", "group", "artifact", "version", "report");
        }

        Path originalJar = Path.of(opts.get("original"));
        Path outputJar = Path.of(opts.get("output"));
        String shadedPattern = opts.getOrDefault("shaded-pattern", opts.get("pattern"));
        RelocationRemapper remapper = new RelocationRemapper(opts.get("pattern"), shadedPattern);

        List<FileMapping> mappings = new ArrayList<>();
        try (JarFile original = new JarFile(originalJar.toFile());
             JarFile output = new JarFile(outputJar.toFile())) {

            for (JarEntry entry : java.util.Collections.list(original.entries())) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                String internalName = entry.getName().substring(0, entry.getName().length() - ".class".length());
                if (!remapper.isInScope(internalName)) {
                    continue; // out of scope for this prototype: only the tracked dependency's own classes
                }

                FileMapping mapping = mode.equals("relocation")
                        ? matchRelocated(original, output, entry, internalName, remapper)
                        : matchIdentity(original, output, entry);
                mappings.add(mapping);
            }
        }

        String subjectSha256 = sha256(Files.readAllBytes(outputJar));
        writeReport(Path.of(opts.get("report")), opts, mode, subjectSha256, mappings);

        long exact = mappings.stream().filter(m -> m.status.equals("exact")).count();
        long transformed = mappings.stream().filter(m -> m.status.equals("transformed")).count();
        long ambiguous = mappings.stream().filter(m -> m.status.equals("ambiguous")).count();
        long unknown = mappings.stream().filter(m -> m.status.equals("unknown")).count();
        System.out.printf("classes in scope: %d | exact: %d | transformed: %d | ambiguous: %d | unknown: %d%n",
                mappings.size(), exact, transformed, ambiguous, unknown);
    }

    private static FileMapping matchRelocated(JarFile original, JarFile output, JarEntry entry,
                                               String internalName, RelocationRemapper remapper) {
        byte[] originalBytes = readAll(original, entry);
        byte[] predictedBytes = remap(originalBytes, remapper);
        String predictedPath = remapper.map(internalName) + ".class";

        FileMapping mapping = new FileMapping();
        mapping.originalPath = entry.getName();
        mapping.predictedOutputPath = predictedPath;
        mapping.originalSha256 = sha256(originalBytes);
        mapping.predictedSha256 = sha256(predictedBytes);

        JarEntry outputEntry = output.getJarEntry(predictedPath);
        if (outputEntry == null) {
            mapping.status = "unknown";
        } else {
            byte[] actualBytes = readAll(output, outputEntry);
            mapping.actualSha256 = sha256(actualBytes);
            mapping.status = mapping.predictedSha256.equals(mapping.actualSha256) ? "transformed" : "ambiguous";
        }
        return mapping;
    }

    private static FileMapping matchIdentity(JarFile original, JarFile output, JarEntry entry) {
        byte[] originalBytes = readAll(original, entry);

        FileMapping mapping = new FileMapping();
        mapping.originalPath = entry.getName();
        mapping.predictedOutputPath = entry.getName(); // no rename expected
        mapping.originalSha256 = sha256(originalBytes);
        mapping.predictedSha256 = mapping.originalSha256; // no transformation applied

        JarEntry outputEntry = output.getJarEntry(entry.getName());
        if (outputEntry == null) {
            mapping.status = "unknown"; // filtered out (e.g. shade exclude / minimizeJar)
        } else {
            byte[] actualBytes = readAll(output, outputEntry);
            mapping.actualSha256 = sha256(actualBytes);
            mapping.status = mapping.originalSha256.equals(mapping.actualSha256) ? "exact" : "ambiguous";
        }
        return mapping;
    }

    private static byte[] remap(byte[] originalBytes, RelocationRemapper remapper) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassWriter writer = new ClassWriter(0);
        ClassRemapper visitor = new ClassRemapper(writer, remapper);
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }

    private static void writeReport(Path reportPath, Map<String, String> opts, String mode, String subjectSha256,
                                     List<FileMapping> mappings) throws IOException {
        long exact = mappings.stream().filter(m -> m.status.equals("exact")).count();
        long transformed = mappings.stream().filter(m -> m.status.equals("transformed")).count();
        long ambiguous = mappings.stream().filter(m -> m.status.equals("ambiguous")).count();
        long unknown = mappings.stream().filter(m -> m.status.equals("unknown")).count();

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema_version\": \"0.1\",\n");
        sb.append("  \"subject_sha256\": \"").append(subjectSha256).append("\",\n");
        sb.append("  \"input_components\": [\n");
        sb.append("    {\n");
        sb.append("      \"purl\": \"pkg:maven/").append(opts.get("group")).append('/').append(opts.get("artifact"))
                .append('@').append(opts.get("version")).append("\",\n");
        sb.append("      \"groupId\": \"").append(esc(opts.get("group"))).append("\",\n");
        sb.append("      \"artifactId\": \"").append(esc(opts.get("artifact"))).append("\",\n");
        sb.append("      \"version\": \"").append(esc(opts.get("version"))).append("\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"transformations\": [\n");
        sb.append("    {\n");
        sb.append("      \"type\": \"").append(mode).append("\",\n");
        sb.append("      \"pattern\": \"").append(esc(opts.get("pattern"))).append("\",\n");
        sb.append("      \"shadedPattern\": \"").append(esc(opts.getOrDefault("shaded-pattern", opts.get("pattern")))).append("\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"file_mappings\": [\n");
        for (int i = 0; i < mappings.size(); i++) {
            FileMapping m = mappings.get(i);
            sb.append("    {\n");
            sb.append("      \"original_path\": \"").append(esc(m.originalPath)).append("\",\n");
            sb.append("      \"predicted_output_path\": \"").append(esc(m.predictedOutputPath)).append("\",\n");
            sb.append("      \"evidence_status\": \"").append(m.status).append("\",\n");
            sb.append("      \"original_sha256\": \"").append(m.originalSha256).append("\",\n");
            sb.append("      \"predicted_sha256\": \"").append(m.predictedSha256).append("\",\n");
            sb.append("      \"actual_sha256\": ").append(m.actualSha256 == null ? "null" : "\"" + m.actualSha256 + "\"").append('\n');
            sb.append("    }").append(i < mappings.size() - 1 ? "," : "").append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"coverage\": {\n");
        sb.append("    \"exact\": ").append(exact).append(",\n");
        sb.append("    \"transformed\": ").append(transformed).append(",\n");
        sb.append("    \"ambiguous\": ").append(ambiguous).append(",\n");
        sb.append("    \"unknown\": ").append(unknown).append(",\n");
        sb.append("    \"total\": ").append(mappings.size()).append('\n');
        sb.append("  }\n");
        sb.append("}\n");

        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(reportPath, sb.toString());
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static byte[] readAll(JarFile jar, JarEntry entry) {
        try (InputStream stream = jar.getInputStream(entry)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                opts.put(args[i].substring(2), args[i + 1]);
                i++;
            }
        }
        return opts;
    }

    private static void require(Map<String, String> opts, String... keys) {
        for (String key : keys) {
            if (!opts.containsKey(key)) {
                throw new IllegalArgumentException("missing required --" + key);
            }
        }
    }

    private static class FileMapping {
        String originalPath;
        String predictedOutputPath;
        String status;
        String originalSha256;
        String predictedSha256;
        String actualSha256;
    }
}
