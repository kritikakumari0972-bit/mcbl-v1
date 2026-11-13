package org.levimc.launcher.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.levimc.launcher.core.minecraft.MinecraftLauncher;
import org.levimc.launcher.core.minecraft.GamePackageManager;
import org.levimc.launcher.core.versions.VersionProfileMetadataStore;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ApkInstaller {

    public interface InstallCallback {
        void onProgress(int progress);

        void onSuccess(String versionName);

        void onError(String errorMessage);
    }

    private static final String APK_FILE_NAME = "base.apk.levi";
    private static final int BUFFER_SIZE = 131072;
    private static final int PROGRESS_PREPARED = 5;
    private static final int PROGRESS_COPY_DONE = 55;
    private static final int PROGRESS_LIBS_DONE = 92;
    private static final int PROGRESS_METADATA_DONE = 96;
    private static final int PROGRESS_MAX = 100;

    private final Context context;
    private final ExecutorService executor;
    private final InstallCallback callback;
    private int lastPostedProgress = -1;

    public ApkInstaller(Context context, ExecutorService executor, InstallCallback callback) {
        this.context = context.getApplicationContext();
        this.executor = executor;
        this.callback = callback;
    }

    public static class VersionAbi {
        public final String versionName;

        public VersionAbi(String versionName) {
            this.versionName = versionName;
        }
    }

    private static final class StagedApk {
        final File file;
        final String entryName;
        final boolean baseApk;

        StagedApk(File file, String entryName, boolean baseApk) {
            this.file = file;
            this.entryName = entryName;
            this.baseApk = baseApk;
        }
    }

    public void install(final Uri apkOrApksUri, final String dirName) {
        executor.submit(() -> {
            File stagingRoot = null;
            try {
                lastPostedProgress = -1;
                postProgress(0);
                stagingRoot = createStagingRoot();
                File stagedApksDir = new File(stagingRoot, "apks");
                if (!stagedApksDir.mkdirs()) throw new IOException("Cannot create temporary APK staging directory.");

                String fileName = getFileName(apkOrApksUri);
                List<StagedApk> stagedApks;
                long sourceSize = getContentSize(apkOrApksUri);
                if (fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".apks")) {
                    stagedApks = extractApksArchive(apkOrApksUri, stagedApksDir, sourceSize);
                } else {
                    File stagedBase = new File(stagedApksDir, "base.apk");
                    try (InputStream input = context.getContentResolver().openInputStream(apkOrApksUri);
                         OutputStream output = new FileOutputStream(stagedBase)) {
                        if (input == null) throw new IOException("Cannot open the selected APK.");
                        copyBounded(input, output, new long[]{0}, ApkSetValidator.MAX_SINGLE_APK_BYTES);
                    }
                    stagedApks = new ArrayList<>();
                    stagedApks.add(new StagedApk(stagedBase, "base.apk", true));
                }

                ApkSetValidator.Validation validation = validateStagedApks(stagedApks);
                postProgress(PROGRESS_PREPARED);
                installValidatedSet(stagedApks, validation, dirName);
            } catch (Exception error) {
                postError("Import rejected: " + error.getMessage());
            } finally {
                if (stagingRoot != null) deleteDir(stagingRoot);
            }
        });
    }

    /**
     * Installs a split APK set already on disk (base APK first, then its splits),
     * such as a complete build downloaded from Google Play. Sources are copied.
     */
    public void installFiles(final List<File> apkFiles, final String dirName) {
        executor.submit(() -> {
            File stagingRoot = null;
            try {
                lastPostedProgress = -1;
                postProgress(0);
                if (apkFiles == null || apkFiles.isEmpty()) {
                    throw new IOException("No APK files were provided.");
                }
                if (apkFiles.size() > ApkSetValidator.MAX_APK_COUNT) {
                    throw new IOException("The APK set contains too many files.");
                }

                stagingRoot = createStagingRoot();
                File stagedApksDir = new File(stagingRoot, "apks");
                if (!stagedApksDir.mkdirs()) throw new IOException("Cannot create temporary APK staging directory.");

                List<StagedApk> stagedApks = new ArrayList<>();
                long[] totalCopied = {0};
                for (int index = 0; index < apkFiles.size(); index++) {
                    File source = apkFiles.get(index);
                    if (source == null || !source.isFile() || source.length() <= 0) {
                        throw new IOException("An APK source file is missing or empty.");
                    }
                    if (source.length() > ApkSetValidator.MAX_SINGLE_APK_BYTES ||
                            totalCopied[0] > ApkSetValidator.MAX_TOTAL_APK_BYTES - source.length()) {
                        throw new IOException("The APK set exceeds the safe import size limit.");
                    }

                    boolean base = index == 0;
                    String entryName = base ? "base.apk" : source.getName();
                    ApkSetValidator.validateArchiveEntryName(entryName);
                    if (base && !source.getName().equalsIgnoreCase("base.apk")) {
                        throw new IOException("The first downloaded APK must be base.apk.");
                    }
                    if (!base && source.getName().equalsIgnoreCase("base.apk")) {
                        throw new IOException("The APK list contains more than one base.apk.");
                    }

                    File stagedFile = new File(stagedApksDir, base ? "base.apk" : source.getName());
                    try (InputStream input = new java.io.FileInputStream(source);
                         OutputStream output = new FileOutputStream(stagedFile)) {
                        copyBounded(input, output, totalCopied, ApkSetValidator.MAX_SINGLE_APK_BYTES);
                    }
                    stagedApks.add(new StagedApk(stagedFile, entryName, base));
                }

                ApkSetValidator.Validation validation = validateStagedApks(stagedApks);
                postProgress(PROGRESS_PREPARED);
                installValidatedSet(stagedApks, validation, dirName);
            } catch (Exception error) {
                postError("Import rejected: " + error.getMessage());
            } finally {
                if (stagingRoot != null) deleteDir(stagingRoot);
            }
        });
    }

    private File createStagingRoot() throws IOException {
        File parent = new File(context.getCacheDir(), "mcbl-import-staging");
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create temporary import storage.");
        }
        File staging = new File(parent, "import-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("Cannot allocate temporary import storage.");
        return staging;
    }

    private List<StagedApk> extractApksArchive(Uri uri, File destination, long sourceSize) throws Exception {
        List<StagedApk> staged = new ArrayList<>();
        Set<String> archivePaths = new HashSet<>();
        Set<String> outputNames = new HashSet<>();
        boolean foundBase = false;
        int entryCount = 0;
        long[] expandedBytes = {0};
        long[] copiedBytes = {0};

        try (InputStream rawInput = context.getContentResolver().openInputStream(uri)) {
            if (rawInput == null) throw new IOException("Cannot open the selected APKS bundle.");
            CountingInputStream countingInput = new CountingInputStream(rawInput);
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(countingInput))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > ApkSetValidator.MAX_ARCHIVE_ENTRIES) {
                        throw new IOException("The APKS archive contains too many entries.");
                    }

                    String rawName = entry.getName();
                    String safeName = entry.isDirectory() && rawName.endsWith("/")
                            ? rawName.substring(0, rawName.length() - 1) : rawName;
                    ApkSetValidator.validateArchiveEntryName(safeName);
                    if (!archivePaths.add(safeName.toLowerCase(Locale.ROOT))) {
                        throw new IOException("Duplicate APKS archive entry: " + safeName);
                    }

                    if (entry.isDirectory()) {
                        zip.closeEntry();
                        continue;
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > ApkSetValidator.MAX_TOTAL_APK_BYTES) {
                        throw new IOException("An APKS archive entry is too large.");
                    }

                    if (!safeName.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                        drainZipEntry(zip, expandedBytes);
                        zip.closeEntry();
                        continue;
                    }

                    if (staged.size() >= ApkSetValidator.MAX_APK_COUNT) {
                        throw new IOException("The APKS bundle contains too many APK files.");
                    }
                    boolean base = ApkSetValidator.isBaseApkEntry(safeName);
                    if (base && foundBase) throw new IOException("The APKS bundle contains multiple base APKs.");
                    String outputName = base ? "base.apk" : new File(safeName).getName();
                    if (!outputNames.add(outputName.toLowerCase(Locale.ROOT))) {
                        throw new IOException("Two APKS entries use the same APK filename: " + outputName);
                    }

                    File outputFile = new File(destination, outputName);
                    long copied = copyZipEntryBounded(zip, new FileOutputStream(outputFile),
                            expandedBytes, ApkSetValidator.MAX_SINGLE_APK_BYTES);
                    copiedBytes[0] += copied;
                    if (sourceSize > 0) {
                        postProgressFromBytes(countingInput.getBytesRead(), sourceSize,
                                PROGRESS_PREPARED, PROGRESS_COPY_DONE);
                    }
                    staged.add(new StagedApk(outputFile, safeName, base));
                    if (base) foundBase = true;
                    zip.closeEntry();
                }
            }
        }

        if (!foundBase) throw new IOException("No base.apk was found in the APKS bundle.");
        if (staged.isEmpty()) throw new IOException("The APKS bundle contains no APK files.");
        return staged;
    }

    private long copyZipEntryBounded(
            InputStream input,
            OutputStream output,
            long[] expandedBytes,
            long maxSingleBytes
    ) throws IOException {
        try (OutputStream safeOutput = output) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long entryBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                entryBytes += read;
                expandedBytes[0] += read;
                if (entryBytes > maxSingleBytes || expandedBytes[0] > ApkSetValidator.MAX_TOTAL_APK_BYTES) {
                    throw new IOException("The expanded APKS bundle exceeds the safe import size limit.");
                }
                safeOutput.write(buffer, 0, read);
            }
            if (entryBytes <= 0) throw new IOException("The APKS bundle contains an empty APK entry.");
            return entryBytes;
        }
    }

    private void drainZipEntry(InputStream input, long[] expandedBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            expandedBytes[0] += read;
            if (expandedBytes[0] > ApkSetValidator.MAX_TOTAL_APK_BYTES) {
                throw new IOException("The expanded APKS bundle exceeds the safe import size limit.");
            }
        }
    }

    private void copyBounded(InputStream input, OutputStream output, long[] totalBytes, long maxSingleBytes)
            throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long currentFileBytes = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            currentFileBytes += read;
            totalBytes[0] += read;
            if (currentFileBytes > maxSingleBytes ||
                    totalBytes[0] > ApkSetValidator.MAX_TOTAL_APK_BYTES) {
                throw new IOException("The selected APK data exceeds the safe import size limit.");
            }
            output.write(buffer, 0, read);
        }
        if (currentFileBytes <= 0) throw new IOException("The selected APK file is empty.");
    }

    private ApkSetValidator.Validation validateStagedApks(List<StagedApk> stagedApks) throws Exception {
        List<ApkSetValidator.ApkDescriptor> descriptors = new ArrayList<>();
        for (StagedApk staged : stagedApks) {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                    staged.file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null) throw new IOException("Android could not read APK metadata: " + staged.entryName);
            String signer = getSignerSha256(info);
            descriptors.add(new ApkSetValidator.ApkDescriptor(
                    staged.entryName,
                    info.packageName,
                    info.versionName,
                    info.getLongVersionCode(),
                    signer,
                    staged.file.length(),
                    staged.baseApk
            ));
        }
        return ApkSetValidator.validate(descriptors);
    }

    private String getSignerSha256(PackageInfo info) throws Exception {
        if (info.signingInfo == null) return "";
        Signature[] signatures = info.signingInfo.getApkContentsSigners();
        if (signatures == null || signatures.length == 0) return "";
        List<String> digests = new ArrayList<>();
        for (Signature signature : signatures) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02X", value & 0xFF));
            digests.add(hex.toString());
        }
        java.util.Collections.sort(digests);
        return String.join(",", digests);
    }

    private void installValidatedSet(
            List<StagedApk> stagedApks,
            ApkSetValidator.Validation validation,
            String dirName
    ) throws Exception {
        if (dirName == null || !dirName.matches("[A-Za-z0-9._]{1,40}")) {
            throw new IOException("Instance name is invalid.");
        }

        File minecraftRoot = LauncherStorage.getMinecraftRoot(context);
        File baseDir = new File(minecraftRoot, LauncherStorage.sanitizeProfileId(dirName));
        if (baseDir.exists()) {
            File[] existing = baseDir.listFiles();
            if (existing != null && existing.length > 0) {
                throw new IOException("An instance with this name already exists. Choose another name; existing worlds and settings were not changed.");
            }
        }
        if (!baseDir.isDirectory() && !baseDir.mkdirs()) {
            throw new IOException("Cannot create the version instance directory.");
        }

        File metadataDir = LauncherStorage.getProfileMetadataDir(context, dirName);
        File libTargetDir = MinecraftLauncher.getRuntimeLibDir(context, dirName);
        boolean targetStarted = true;
        try {
            if (libTargetDir.exists() && !deleteDir(libTargetDir)) {
                throw new IOException("Cannot prepare the native library cache directory.");
            }

            Map<String, StagedApk> byEntry = new HashMap<>();
            for (StagedApk staged : stagedApks) byEntry.put(staged.entryName, staged);

            List<File> apkFilesToExtract = new ArrayList<>();
            long[] installedBytes = {0};
            for (ApkSetValidator.ApkDescriptor descriptor : validation.getApks()) {
                StagedApk staged = byEntry.get(descriptor.entryName);
                if (staged == null) throw new IOException("Validated APK was not staged: " + descriptor.entryName);

                File output;
                if (descriptor.baseApk) {
                    output = new File(baseDir, APK_FILE_NAME);
                } else {
                    File splitsDir = new File(baseDir, "splits");
                    if (!splitsDir.isDirectory() && !splitsDir.mkdirs()) {
                        throw new IOException("Cannot create split APK directory.");
                    }
                    output = new File(splitsDir, ApkSetValidator.splitOutputName(descriptor.entryName));
                }

                try (InputStream input = new java.io.FileInputStream(staged.file);
                     OutputStream outputStream = new FileOutputStream(output)) {
                    copyStream(input, outputStream, bytesCopied -> {
                        installedBytes[0] += bytesCopied;
                        if (validation.getTotalBytes() > 0) {
                            postProgressFromBytes(installedBytes[0], validation.getTotalBytes(),
                                    PROGRESS_PREPARED, PROGRESS_COPY_DONE);
                        }
                    });
                }
                apkFilesToExtract.add(output);
            }

            finishInstall(apkFilesToExtract, libTargetDir, baseDir, metadataDir, dirName);
        } catch (Exception error) {
            if (targetStarted) {
                deleteDir(baseDir);
                deleteDir(libTargetDir);
            }
            throw error;
        }
    }

    private void finishInstall(List<File> apkFilesToExtract, File libTargetDir, File baseDir,
                               File metadataDir, String dirName) throws IOException {
        postProgress(PROGRESS_COPY_DONE);
        extractNativeLibsWithProgress(apkFilesToExtract, libTargetDir);

        String versionName = extractVersionName(baseDir, dirName);
        GamePackageManager.recordImportedLibraries(context, dirName, versionName, apkFilesToExtract);
        postProgress(PROGRESS_METADATA_DONE);
        writeProfileMetadata(metadataDir, dirName, versionName);

        postProgress(PROGRESS_MAX);
        postSuccess(versionName);
    }

    private String extractVersionName(File baseDir, String dirName) {
        File baseApkLevi = new File(baseDir, APK_FILE_NAME);
        if (baseApkLevi.exists()) {
            String v = extractApkVersionName(baseApkLevi);
            if (!"unknown_version".equals(v)) return v;
        }
        String name = dirName;
        if (name.startsWith("Minecraft_")) {
            name = name.substring("Minecraft_".length());
        }
        return name;
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        copyStream(is, os, null);
    }

    private static void copyStream(InputStream is, OutputStream os, ProgressReporter reporter) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int len;
        while ((len = is.read(buffer)) != -1) {
            os.write(buffer, 0, len);
            if (reporter != null) {
                reporter.onBytesCopied(len);
            }
        }
    }

    private static void writeProfileMetadata(File metadataDir, String dirName, String versionName) throws IOException {
        VersionProfileMetadataStore store = new VersionProfileMetadataStore();
        store.loadOrCreate(metadataDir, VersionProfileMetadataStore.Defaults.custom(dirName, versionName));
    }

    private void postProgress(int progress) {
        int clamped = Math.max(0, Math.min(100, progress));
        if (clamped <= lastPostedProgress && clamped < 100) {
            return;
        }
        lastPostedProgress = clamped;
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onProgress(clamped);
        });
    }

    private void postProgressFromBytes(long processedBytes, long totalBytes, int start, int end) {
        if (totalBytes <= 0) {
            return;
        }
        long safeProcessed = Math.max(0, Math.min(processedBytes, totalBytes));
        int progress = start + (int) ((safeProcessed * (end - start)) / totalBytes);
        postProgress(progress);
    }

    private void extractNativeLibsWithProgress(List<File> apkFiles, File libTargetDir) throws IOException {
        long totalLibBytes = calculateNativeLibBytes(apkFiles);
        if (totalLibBytes <= 0) {
            postProgress(PROGRESS_LIBS_DONE);
            return;
        }

        long[] extractedBytes = {0};
        for (File apkFile : apkFiles) {
            try (ZipFile zip = new ZipFile(apkFile)) {
                ApkUtils.unzipLibsToSystemAbi(libTargetDir, zip, bytesExtracted -> {
                    extractedBytes[0] += bytesExtracted;
                    postProgressFromBytes(extractedBytes[0], totalLibBytes, PROGRESS_COPY_DONE, PROGRESS_LIBS_DONE);
                });
            }
        }
        postProgress(PROGRESS_LIBS_DONE);
    }

    private long calculateNativeLibBytes(List<File> apkFiles) {
        long total = 0;
        for (File apkFile : apkFiles) {
            try (ZipFile zipFile = new ZipFile(apkFile)) {
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith("lib/")) {
                        continue;
                    }
                    long size = entry.getSize();
                    if (size > 0) {
                        total += size;
                    }
                }
            } catch (IOException ignored) {}
        }
        return total;
    }

    private void postSuccess(String versionName) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onSuccess(versionName);
        });
    }

    private void postError(String error) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (callback != null) callback.onError(error);
        });
    }

    private String extractApkVersionName(File apkFile) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (info != null) {
                String pkgName = info.packageName;
                String vName = info.versionName;
                if ("com.mojang.minecraftpe".equals(pkgName) && vName != null && !vName.isEmpty()) {
                    return vName;
                }
            }
        } catch (Exception ignored) {
        }
        return "unknown_version";
    }

    private String getFileName(Uri uri) {
        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        String result = null;
        if (cursor != null) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameIndex != -1 && cursor.moveToFirst()) {
                result = cursor.getString(nameIndex);
            }
            cursor.close();
        }
        return result;
    }

    private long getContentSize(Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }

        Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex);
                }
            } finally {
                cursor.close();
            }
        }

        try (ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd != null) {
                return fd.getStatSize();
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    private interface ProgressReporter {
        void onBytesCopied(int bytesCopied);
    }

    private static class CountingInputStream extends FilterInputStream {
        private long bytesRead;

        CountingInputStream(InputStream in) {
            super(in);
        }

        long getBytesRead() {
            return bytesRead;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) bytesRead += read;
            return read;
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir == null || !dir.exists()) return true;
        if (dir.isFile()) return dir.delete();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File child : files) {
                if (!deleteDir(child)) return false;
            }
        }
        return dir.delete();
    }
}
