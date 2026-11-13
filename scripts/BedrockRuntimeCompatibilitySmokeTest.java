import org.levimc.launcher.core.minecraft.BedrockRuntimeCompatibility;

/** Dependency-free smoke tests for the Android runtime gate; executed by GitHub Actions. */
public final class BedrockRuntimeCompatibilitySmokeTest {
    private static final String[] ARM64 = {"arm64-v8a"};

    public static void main(String[] args) {
        check(BedrockRuntimeCompatibility.compareVersions("1.21.100", "1.21.80") > 0,
                "1.21.100 must sort after 1.21.80");
        check(BedrockRuntimeCompatibility.compareVersions("26.0", "1.21.80") > 0,
                "modern major versions must be handled numerically");
        check(BedrockRuntimeCompatibility.versionBlocker("1.21.79") != null,
                "older releases must be rejected");
        check(BedrockRuntimeCompatibility.versionBlocker("1.21.80") == null,
                "minimum supported release must be accepted");

        BedrockRuntimeCompatibility.Report supported = evaluate(
                "1.21.100", 35, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        check(supported.canLaunch(), "compatible ARM64 device must pass");
        check(supported.getWarnings().isEmpty(), "healthy device must have no warnings");

        BedrockRuntimeCompatibility.Report oldAndroid = evaluate(
                "1.21.100", 27, true, ARM64, 4L << 30, 2L << 30, 8L << 30);
        check(!oldAndroid.canLaunch(), "Android below API 28 must be blocked");

        BedrockRuntimeCompatibility.Report nonArm64 = evaluate(
                "1.21.100", 35, true, new String[]{"x86_64"}, 4L << 30, 2L << 30, 8L << 30);
        check(!nonArm64.canLaunch(), "non-ARM64 runtime must be blocked");

        BedrockRuntimeCompatibility.Report lowResources = evaluate(
                "1.21.100", 35, true, ARM64, 2L << 30, 700L << 20, 1L << 30);
        check(lowResources.canLaunch(), "low resources are warnings, not false hard failures");
        check(lowResources.getWarnings().size() == 3, "expected total RAM, available RAM and storage warnings");

        System.out.println("Bedrock runtime compatibility smoke tests passed.");
    }

    private static BedrockRuntimeCompatibility.Report evaluate(
            String version, int api, boolean process64Bit, String[] abis,
            long totalRam, long availableRam, long freeStorage) {
        return BedrockRuntimeCompatibility.evaluate(
                version, api, process64Bit, abis, totalRam, availableRam, freeStorage);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
