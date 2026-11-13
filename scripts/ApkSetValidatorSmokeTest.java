import org.levimc.launcher.util.ApkSetValidator;
import java.util.Arrays;
import java.util.Collections;

/** Plain-JDK smoke checks for APK-set safety rules. */
public final class ApkSetValidatorSmokeTest {
    private static ApkSetValidator.ApkDescriptor apk(String name, boolean base, String version, long code, String signer) {
        return new ApkSetValidator.ApkDescriptor(name, ApkSetValidator.MINECRAFT_PACKAGE,
                version, code, signer, 1024, base);
    }

    public static void main(String[] args) throws Exception {
        ApkSetValidator.Validation valid = ApkSetValidator.validate(Arrays.asList(
                apk("splits/config.arm64.apk", false, "1.21.100", 121100, "ABCD"),
                apk("base.apk", true, "1.21.100", 121100, "abcd")));
        check(valid.getApks().get(0).baseApk, "base APK must be first after validation");
        check(valid.getApks().size() == 2, "split APK should be retained");

        expectInvalid(Collections.singletonList(apk("split.apk", false, "1.21.100", 121100, "ABCD")));
        expectInvalid(Arrays.asList(
                apk("base.apk", true, "1.21.100", 121100, "ABCD"),
                apk("split.apk", false, "1.21.90", 121090, "ABCD")));
        expectInvalid(Arrays.asList(
                apk("base.apk", true, "1.21.100", 121100, "ABCD"),
                apk("split.apk", false, "1.21.100", 121100, "DIFF")));
        expectUnsafe("../base.apk");
        expectUnsafe("splits\\base.apk");
        System.out.println("APK split-set validation smoke tests passed.");
    }

    private static void expectInvalid(java.util.List<ApkSetValidator.ApkDescriptor> entries) throws Exception {
        try {
            ApkSetValidator.validate(entries);
            throw new AssertionError("Invalid APK set accepted");
        } catch (ApkSetValidator.ValidationException expected) {
            // expected
        }
    }

    private static void expectUnsafe(String path) throws Exception {
        try {
            ApkSetValidator.validateArchiveEntryName(path);
            throw new AssertionError("Unsafe zip path accepted: " + path);
        } catch (ApkSetValidator.ValidationException expected) {
            // expected
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
