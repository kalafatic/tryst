package eu.kalafatic.tryst.infrastructure.build;

import eu.kalafatic.tryst.core.log.TrystLog;
import eu.kalafatic.tryst.domain.build.BuildConfiguration;
import eu.kalafatic.tryst.domain.build.BuildSystemType;
import eu.kalafatic.tryst.domain.build.BuildTechnologyDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default multi-technology detector that identifies project build systems
 * and available build/test commands.
 * Supports Maven, Gradle, Node/npm, Cargo (Rust), Python, and custom .tryst-build.json.
 */
public class DefaultBuildTechnologyDetector implements BuildTechnologyDetector {

    public static final String CONFIG_FILE_NAME = ".tryst-build.json";

    @Override
    public BuildSystemType detectBuildSystem(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");

        // 1. Check for custom build config first
        BuildConfiguration custom = loadConfiguration(repositoryRoot);
        if (custom != null && custom.buildSystem() != null && !custom.buildSystem().isBlank()) {
            try {
                return BuildSystemType.valueOf(custom.buildSystem().toUpperCase());
            } catch (IllegalArgumentException e) {
                return BuildSystemType.CUSTOM;
            }
        }
        if (custom != null && (!custom.buildCommand().isEmpty() || !custom.testCommand().isEmpty())) {
            return BuildSystemType.CUSTOM;
        }

        // 2. Maven detection
        if (Files.exists(repositoryRoot.resolve("pom.xml"))) {
            return BuildSystemType.MAVEN;
        }

        // 3. Gradle detection
        if (Files.exists(repositoryRoot.resolve("build.gradle")) ||
            Files.exists(repositoryRoot.resolve("build.gradle.kts")) ||
            Files.exists(repositoryRoot.resolve("gradlew")) ||
            Files.exists(repositoryRoot.resolve("gradlew.bat"))) {
            return BuildSystemType.GRADLE;
        }

        // 4. Node / npm detection
        if (Files.exists(repositoryRoot.resolve("package.json"))) {
            return BuildSystemType.NPM;
        }

        // 5. Cargo / Rust detection
        if (Files.exists(repositoryRoot.resolve("Cargo.toml"))) {
            return BuildSystemType.CARGO;
        }

        // 6. Python detection
        if (Files.exists(repositoryRoot.resolve("pyproject.toml")) ||
            Files.exists(repositoryRoot.resolve("setup.py")) ||
            Files.exists(repositoryRoot.resolve("requirements.txt")) ||
            Files.exists(repositoryRoot.resolve("tox.ini"))) {
            return BuildSystemType.PYTHON;
        }

        return BuildSystemType.UNKNOWN;
    }

    @Override
    public List<String> resolveBuildCommand(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        BuildConfiguration config = loadConfiguration(repositoryRoot);
        if (config != null && !config.buildCommand().isEmpty()) {
            return config.buildCommand();
        }

        BuildSystemType system = detectBuildSystem(repositoryRoot);
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        switch (system) {
            case MAVEN -> {
                String mavenBin = resolveMavenExecutable(repositoryRoot, isWindows);
                return List.of(mavenBin, "-B", "compile");
            }
            case GRADLE -> {
                String gradleBin = resolveGradleExecutable(repositoryRoot, isWindows);
                return List.of(gradleBin, "--no-daemon", "classes");
            }
            case NPM -> {
                String npmBin = isWindows ? "npm.cmd" : "npm";
                return List.of(npmBin, "run", "build", "--if-present");
            }
            case CARGO -> {
                return List.of("cargo", "check");
            }
            case PYTHON -> {
                String pyBin = isWindows ? "python.exe" : "python3";
                return List.of(pyBin, "-m", "compileall", ".");
            }
            default -> {
                return List.of();
            }
        }
    }

    @Override
    public List<String> resolveTestCommand(Path repositoryRoot) {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
        BuildConfiguration config = loadConfiguration(repositoryRoot);
        if (config != null && !config.testCommand().isEmpty()) {
            return config.testCommand();
        }

        BuildSystemType system = detectBuildSystem(repositoryRoot);
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        switch (system) {
            case MAVEN -> {
                String mavenBin = resolveMavenExecutable(repositoryRoot, isWindows);
                return List.of(mavenBin, "-B", "test");
            }
            case GRADLE -> {
                String gradleBin = resolveGradleExecutable(repositoryRoot, isWindows);
                return List.of(gradleBin, "--no-daemon", "test");
            }
            case NPM -> {
                String npmBin = isWindows ? "npm.cmd" : "npm";
                return List.of(npmBin, "test", "--if-present");
            }
            case CARGO -> {
                return List.of("cargo", "test");
            }
            case PYTHON -> {
                String pyBin = isWindows ? "python.exe" : "python3";
                return List.of(pyBin, "-m", "unittest", "discover");
            }
            default -> {
                return List.of();
            }
        }
    }

    private String resolveMavenExecutable(Path repositoryRoot, boolean isWindows) {
        Path wrapper = repositoryRoot.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper)) {
            return isWindows ? wrapper.toString() : "./mvnw";
        }
        return isWindows ? "mvn.cmd" : "mvn";
    }

    private String resolveGradleExecutable(Path repositoryRoot, boolean isWindows) {
        Path wrapper = repositoryRoot.resolve(isWindows ? "gradlew.bat" : "gradlew");
        if (Files.isRegularFile(wrapper)) {
            return isWindows ? wrapper.toString() : "./gradlew";
        }
        return isWindows ? "gradle.bat" : "gradle";
    }

    @Override
    public BuildConfiguration loadConfiguration(Path repositoryRoot) {
        if (repositoryRoot == null) {
            return BuildConfiguration.defaults();
        }
        Path configFile = repositoryRoot.resolve(CONFIG_FILE_NAME);
        if (!Files.exists(configFile) || !Files.isRegularFile(configFile)) {
            return BuildConfiguration.defaults();
        }

        try {
            String content = Files.readString(configFile).trim();
            if (content.isEmpty()) {
                return BuildConfiguration.defaults();
            }
            return parseSimpleJsonConfig(content);
        } catch (IOException e) {
            TrystLog.warn(DefaultBuildTechnologyDetector.class, "Failed reading " + CONFIG_FILE_NAME + ": " + e.getMessage());
            return BuildConfiguration.defaults();
        }
    }

    private BuildConfiguration parseSimpleJsonConfig(String json) {
        String buildSystem = null;
        List<String> buildCmd = new ArrayList<>();
        List<String> testCmd = new ArrayList<>();
        Integer timeout = null;
        Map<String, String> env = new HashMap<>();

        try {
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return BuildConfiguration.defaults();
            }

            buildSystem = extractStringValue(json, "buildSystem");
            timeout = extractIntValue(json, "timeoutSeconds");
            buildCmd = extractStringArray(json, "buildCommand");
            testCmd = extractStringArray(json, "testCommand");

        } catch (Exception e) {
            TrystLog.warn(DefaultBuildTechnologyDetector.class, "Error parsing " + CONFIG_FILE_NAME + ": " + e.getMessage());
        }

        return new BuildConfiguration(buildSystem, buildCmd, testCmd, timeout, env);
    }

    private static String extractStringValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) idx = json.indexOf("'" + key + "'");
        if (idx == -1) return null;

        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;

        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 == -1) quote1 = json.indexOf('\'', colon + 1);
        if (quote1 == -1) return null;

        char q = json.charAt(quote1);
        int quote2 = json.indexOf(q, quote1 + 1);
        if (quote2 == -1) return null;

        return json.substring(quote1 + 1, quote2).trim();
    }

    private static Integer extractIntValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) idx = json.indexOf("'" + key + "'");
        if (idx == -1) return null;

        int colon = json.indexOf(':', idx);
        if (colon == -1) return null;

        int end = colon + 1;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || Character.isWhitespace(json.charAt(end)))) {
            end++;
        }
        String val = json.substring(colon + 1, end).trim();
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> extractStringArray(String json, String key) {
        List<String> list = new ArrayList<>();
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) idx = json.indexOf("'" + key + "'");
        if (idx == -1) return list;

        int bracketOpen = json.indexOf('[', idx);
        if (bracketOpen == -1) return list;
        int bracketClose = json.indexOf(']', bracketOpen + 1);
        if (bracketClose == -1) return list;

        String inner = json.substring(bracketOpen + 1, bracketClose).trim();
        if (inner.isEmpty()) {
            return list;
        }

        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '"' || c == '\'') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                String token = current.toString().trim();
                if (!token.isEmpty()) {
                    list.add(token);
                }
                current.setLength(0);
            } else if (inQuotes) {
                current.append(c);
            }
        }
        String token = current.toString().trim();
        if (!token.isEmpty()) {
            list.add(token);
        }

        return list;
    }
}
