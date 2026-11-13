package org.levimc.launcher.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates a complete Minecraft Bedrock APK set before an instance directory
 * is changed. Kept free of Android dependencies so its rules can run in CI.
 */
public final class ApkSetValidator {
    public static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";
    public static final int MAX_APK_COUNT = 64;
    public static final long MAX_SINGLE_APK_BYTES = 1024L * 1024L * 1024L;
    public static final long MAX_TOTAL_APK_BYTES = 2L * 1024L * 1024L * 1024L;
    public static final int MAX_ARCHIVE_ENTRIES = 256;

    private ApkSetValidator() {
    }

    public static final class ApkDescriptor {
        public final String entryName;
        public final String packageName;
        public final String versionName;
        public final long versionCode;
        public final String signerSha256;
        public final long sizeBytes;
        public final boolean baseApk;

        public ApkDescriptor(
                String entryName,
                String packageName,
                String versionName,
                long versionCode,
                String signerSha256,
                long sizeBytes,
                boolean baseApk
        ) {
            this.entryName = entryName;
            this.packageName = packageName;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.sizeBytes = sizeBytes;
            this.baseApk = baseApk;
        }
    }

    public static final class Validation {
        private final String versionName;
        private final long versionCode;
        private final String signerSha256;
        private final long totalBytes;
        private final List<ApkDescriptor> apks;

        private Validation(
                String versionName,
                long versionCode,
                String signerSha256,
                long totalBytes,
                List<ApkDescriptor> apks
        ) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.signerSha256 = signerSha256;
            this.totalBytes = totalBytes;
            this.apks = Collections.unmodifiableList(new ArrayList<>(apks));
        }

        public String getVersionName() { return versionName; }
        public long getVersionCode() { return versionCode; }
        public String getSignerSha256() { return signerSha256; }
        public long getTotalBytes() { return totalBytes; }
        public List<ApkDescriptor> getApks() { return apks; }
    }

    public static final class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }

    public static Validation validate(List<ApkDescriptor> apks) throws ValidationException {
        if (apks == null || apks.isEmpty()) {
            throw new ValidationException("The APK set is empty.");
        }
        if (apks.size() > MAX_APK_COUNT) {
            throw new ValidationException("The APK set contains too many files (maximum " + MAX_APK_COUNT + ").");
        }

        Set<String> entryNames = new HashSet<>();
        Set<String> outputNames = new HashSet<>();
        List<ApkDescriptor> validated = new ArrayList<>();
        ApkDescriptor base = null;
        long totalBytes = 0L;

        for (ApkDescriptor apk : apks) {
            if (apk == null) throw new ValidationException("The APK set contains an empty file entry.");
            validateArchiveEntryName(apk.entryName);
            String canonicalName = apk.entryName.toLowerCase(Locale.ROOT);
            if (!entryNames.add(canonicalName)) {
                throw new ValidationException("Duplicate APK entry: " + apk.entryName);
            }

            if (!apk.entryName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                throw new ValidationException("Not an APK file: " + apk.entryName);
            }
            String outputName = apk.baseApk ? "base.apk" : splitOutputName(apk.entryName);
            if (!outputNames.add(outputName.toLowerCase(Locale.ROOT))) {
                throw new ValidationException("Two APK entries have the same output filename: " + outputName);
            }

            if (apk.sizeBytes <= 0 || apk.sizeBytes > MAX_SINGLE_APK_BYTES) {
                throw new ValidationException("APK has an invalid or excessive size: " + apk.entryName);
            }
            if (totalBytes > MAX_TOTAL_APK_BYTES - apk.sizeBytes) {
                throw new ValidationException("The expanded APK set exceeds the 2 GiB safety limit.");
            }
            totalBytes += apk.sizeBytes;

            if (!MINECRAFT_PACKAGE.equals(apk.packageName)) {
                throw new ValidationException("APK is not part of Minecraft Bedrock: " + apk.entryName);
            }
            if (apk.versionName == null || apk.versionName.trim().isEmpty() || apk.versionCode <= 0) {
                throw new ValidationException("APK is missing its version metadata: " + apk.entryName);
            }
            if (apk.signerSha256 == null || apk.signerSha256.trim().isEmpty()) {
                throw new ValidationException("Cannot verify the APK signing certificate: " + apk.entryName);
            }
            if (apk.baseApk) {
                if (base != null) throw new ValidationException("The APK set contains more than one base.apk.");
                base = apk;
            }
            validated.add(apk);
        }

        if (base == null) {
            throw new ValidationException("No base.apk was found in the APK set.");
        }

        for (ApkDescriptor apk : validated) {
            if (apk.versionCode != base.versionCode ||
                    !base.versionName.equals(apk.versionName)) {
                throw new ValidationException("APK version metadata does not match base.apk: " + apk.entryName);
            }
            if (!base.signerSha256.equalsIgnoreCase(apk.signerSha256)) {
                throw new ValidationException("APK signing certificates do not match: " + apk.entryName);
            }
        }

        // The game runtime and native library extractor expect base.apk first.
        List<ApkDescriptor> baseFirst = new ArrayList<>();
        baseFirst.add(base);
        for (ApkDescriptor apk : validated) {
            if (apk != base) baseFirst.add(apk);
        }
        return new Validation(base.versionName, base.versionCode, base.signerSha256, totalBytes, baseFirst);
    }

    /**
     * Accepts relative zip paths only. Path components are intentionally
     * restrictive because APK bundle paths are never allowed to be destinations.
     */
    public static void validateArchiveEntryName(String entryName) throws ValidationException {
        if (entryName == null || entryName.isEmpty() || entryName.startsWith("/") ||
                entryName.startsWith("\\") || entryName.indexOf('\\') >= 0 ||
                entryName.indexOf('\0') >= 0) {
            throw new ValidationException("Unsafe archive entry path.");
        }
        String[] components = entryName.split("/", -1);
        for (String component : components) {
            if (component.isEmpty() || ".".equals(component) || "..".equals(component)) {
                throw new ValidationException("Unsafe archive entry path: " + entryName);
            }
        }
    }

    public static boolean isBaseApkEntry(String entryName) {
        return entryName != null && (entryName.equalsIgnoreCase("base.apk") || entryName.toLowerCase(Locale.ROOT).endsWith("/base.apk"));
    }

    public static String splitOutputName(String entryName) throws ValidationException {
        validateArchiveEntryName(entryName);
        int slash = entryName.lastIndexOf('/');
        String name = slash >= 0 ? entryName.substring(slash + 1) : entryName;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".apk") || "base.apk".equalsIgnoreCase(name)) {
            throw new ValidationException("Invalid split APK filename: " + entryName);
        }
        String stem = name.substring(0, name.length() - 4);
        if (!stem.matches("[A-Za-z0-9._-]{1,180}")) {
            throw new ValidationException("Invalid split APK filename: " + entryName);
        }
        return stem + ".apk.levi";
    }
}
