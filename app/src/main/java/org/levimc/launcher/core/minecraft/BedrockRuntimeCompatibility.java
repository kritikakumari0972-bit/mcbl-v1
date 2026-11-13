package org.levimc.launcher.core.minecraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central compatibility policy for the Android Bedrock runtime.
 *
 * The policy intentionally separates hard runtime requirements from device
 * recommendations: low memory or storage produces warnings, not fake guarantees.
 */
public final class BedrockRuntimeCompatibility {
    public static final String MINIMUM_GAME_VERSION = "1.21.80";
    public static final int MINIMUM_ANDROID_API = 28;
    public static final long MINIMUM_AVAILABLE_RAM_BYTES = 1L * 1024L * 1024L * 1024L;
    public static final long RECOMMENDED_TOTAL_RAM_BYTES = 3L * 1024L * 1024L * 1024L;
    public static final long RECOMMENDED_FREE_STORAGE_BYTES = 2L * 1024L * 1024L * 1024L;

    private static final Pattern NUMERIC_VERSION =
            Pattern.compile("^\\s*[vV]?(\\d+(?:\\.\\d+)*)(?:[-+].*)?\\s*$");

    private BedrockRuntimeCompatibility() {
    }

    public static final class Report {
        private final List<String> blockers;
        private final List<String> warnings;

        private Report(List<String> blockers, List<String> warnings) {
            this.blockers = Collections.unmodifiableList(new ArrayList<>(blockers));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public List<String> getBlockers() {
            return blockers;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean canLaunch() {
            return blockers.isEmpty();
        }

        public String blockerSummary() {
            return String.join("\\n", blockers);
        }

        public String warningSummary() {
            return String.join(" • ", warnings);
        }
    }

    /**
     * Returns a launch blocker for versions older than the currently supported
     * native runtime baseline. An unrecognised version is not silently guessed;
     * the caller can display a warning and preserve the detailed launch trace.
     */
    public static String versionBlocker(String versionName) {
        int comparison = compareVersions(versionName, MINIMUM_GAME_VERSION);
        if (comparison == Integer.MIN_VALUE) {
            return null;
        }
        if (comparison < 0) {
            return "Minecraft Bedrock " + versionName + " is older than the supported Android runtime baseline "
                    + MINIMUM_GAME_VERSION + ".";
        }
        return null;
    }

    /**
     * Numeric component comparison (1.21.100 is newer than 1.21.80).
     * Preview/build suffixes are ignored after the numeric version core.
     *
     * @return negative/zero/positive, or Integer.MIN_VALUE when the input is unknown.
     */
    public static int compareVersions(String left, String right) {
        int[] a = parseNumericVersion(left);
        int[] b = parseNumericVersion(right);
        if (a == null || b == null) {
            return Integer.MIN_VALUE;
        }

        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int[] parseNumericVersion(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = NUMERIC_VERSION.matcher(value);
        if (!matcher.matches()) {
            return null;
        }

        String[] parts = matcher.group(1).split("\\.");
        if (parts.length == 0 || parts.length > 8) {
            return null;
        }
        int[] parsed = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                parsed[i] = Integer.parseInt(parts[i]);
            }
            return parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Evaluates hard runtime requirements and non-blocking hardware warnings.
     * Unknown RAM/storage values (<= 0) are reported as unavailable, not treated
     * as zero and not used to reject a device.
     */
    public static Report evaluate(
            String gameVersion,
            int androidApi,
            boolean processIs64Bit,
            String[] supported64BitAbis,
            long totalRamBytes,
            long availableRamBytes,
            long freeStorageBytes
    ) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String versionProblem = versionBlocker(gameVersion);
        if (versionProblem != null) {
            blockers.add(versionProblem);
        } else if (compareVersions(gameVersion, MINIMUM_GAME_VERSION) == Integer.MIN_VALUE) {
            warnings.add("The game version could not be parsed; compatibility cannot be confirmed before launch.");
        }

        if (androidApi < MINIMUM_ANDROID_API) {
            blockers.add("Android API " + androidApi + " is below the required API " + MINIMUM_ANDROID_API + ".");
        }

        boolean arm64Available = false;
        if (supported64BitAbis != null) {
            for (String abi : supported64BitAbis) {
                if ("arm64-v8a".equals(abi)) {
                    arm64Available = true;
                    break;
                }
            }
        }
        if (!processIs64Bit || !arm64Available) {
            blockers.add("This Bedrock runtime requires a 64-bit ARM device (arm64-v8a).");
        }

        if (totalRamBytes > 0 && totalRamBytes < RECOMMENDED_TOTAL_RAM_BYTES) {
            warnings.add("Device RAM is below the recommended 3 GB; larger worlds or add-ons may be unstable.");
        }
        if (availableRamBytes > 0 && availableRamBytes < MINIMUM_AVAILABLE_RAM_BYTES) {
            warnings.add("Less than 1 GB RAM is currently available; Android may stop Minecraft during startup or gameplay.");
        }
        if (freeStorageBytes > 0 && freeStorageBytes < RECOMMENDED_FREE_STORAGE_BYTES) {
            warnings.add("Less than 2 GB storage is free; game data, updates or worlds may run out of space.");
        }

        return new Report(blockers, warnings);
    }
}
