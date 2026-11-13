package org.levimc.launcher.core.minecraft;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BedrockRuntimeCompatibilityTest {
    private static final String[] ARM64 = {"arm64-v8a"};

    @Test
    public void numericComponentsAreComparedNumerically() {
        assertTrue(BedrockRuntimeCompatibility.compareVersions("1.21.100", "1.21.80") > 0);
        assertTrue(BedrockRuntimeCompatibility.compareVersions("1.21.70", "1.21.80") < 0);
        assertEquals(0, BedrockRuntimeCompatibility.compareVersions("1.21.80.0", "1.21.80"));
    }

    @Test
    public void modernMajorVersionsAreNotMisreadAsOldReleases() {
        assertTrue(BedrockRuntimeCompatibility.compareVersions("26.0", "1.21.80") > 0);
        assertNull(BedrockRuntimeCompatibility.versionBlocker("26.0.0"));
    }

    @Test
    public void previewAndBuildSuffixesDoNotBreakVersionParsing() {
        assertTrue(BedrockRuntimeCompatibility.compareVersions("1.21.80-preview.2", "1.21.80") >= 0);
        assertNull(BedrockRuntimeCompatibility.versionBlocker("1.21.80+build.4"));
    }

    @Test
    public void rejectsVersionsBelowNativeRuntimeBaseline() {
        assertTrue(BedrockRuntimeCompatibility.versionBlocker("1.21.79").contains("1.21.80"));
        BedrockRuntimeCompatibility.Report report = evaluate("1.20.70", 35, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        assertFalse(report.canLaunch());
        assertTrue(report.getBlockers().get(0).contains("older than the supported Android runtime baseline"));
    }

    @Test
    public void acceptsSupportedGameOnArm64Android() {
        BedrockRuntimeCompatibility.Report report = evaluate("1.21.80", 35, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        assertTrue(report.canLaunch());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    public void newerPatchBuildIsAccepted() {
        assertNull(BedrockRuntimeCompatibility.versionBlocker("1.21.100"));
    }

    @Test
    public void rejectsAndroidBelowApiBaseline() {
        BedrockRuntimeCompatibility.Report report = evaluate("1.21.80", 27, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        assertFalse(report.canLaunch());
        assertTrue(report.blockerSummary().contains("Android API 27"));
    }

    @Test
    public void rejects32BitProcessAndMissingArm64Abi() {
        BedrockRuntimeCompatibility.Report processReport = evaluate("1.21.80", 35, false, ARM64, 4L << 30, 2L << 30, 8L << 30);
        assertFalse(processReport.canLaunch());
        assertTrue(processReport.blockerSummary().contains("64-bit ARM"));

        BedrockRuntimeCompatibility.Report abiReport = evaluate("1.21.80", 35, true, new String[]{"armeabi-v7a"}, 4L << 30, 2L << 30, 8L << 30);
        assertFalse(abiReport.canLaunch());
    }

    @Test
    public void lowMemoryAndStorageAreWarningsRatherThanHardBlocks() {
        BedrockRuntimeCompatibility.Report report = evaluate(
                "1.21.80", 35, true, ARM64, 2L << 30, 700L << 20, 1L << 30
        );
        assertTrue(report.canLaunch());
        assertEquals(3, report.getWarnings().size());
        assertTrue(report.warningSummary().contains("3 GB"));
        assertTrue(report.warningSummary().contains("1 GB"));
        assertTrue(report.warningSummary().contains("2 GB"));
    }

    @Test
    public void unknownHardwareMeasurementsDoNotBecomeZeroByteFailures() {
        BedrockRuntimeCompatibility.Report report = evaluate("1.21.80", 35, true, ARM64, -1, -1, -1);
        assertTrue(report.canLaunch());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    public void unknownGameVersionIsWarnedButNotGuessed() {
        BedrockRuntimeCompatibility.Report report = evaluate("preview-build-unknown", 35, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        assertTrue(report.canLaunch());
        assertTrue(report.warningSummary().contains("could not be parsed"));
    }

    private BedrockRuntimeCompatibility.Report evaluate(
            String gameVersion,
            int api,
            boolean process64Bit,
            String[] abis,
            long totalRam,
            long availableRam,
            long freeStorage
    ) {
        return BedrockRuntimeCompatibility.evaluate(
                gameVersion, api, process64Bit, abis, totalRam, availableRam, freeStorage
        );
    }
}
