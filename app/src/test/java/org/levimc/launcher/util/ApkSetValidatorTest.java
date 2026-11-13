package org.levimc.launcher.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ApkSetValidatorTest {
    private ApkSetValidator.ApkDescriptor base() {
        return new ApkSetValidator.ApkDescriptor(
                "base.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "AABBCC", 1000, true);
    }

    private ApkSetValidator.ApkDescriptor split(String name) {
        return new ApkSetValidator.ApkDescriptor(
                "splits/" + name + ".apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "aabbcc", 500, false);
    }

    @Test
    public void acceptsBaseAndMatchingSplitsAndReturnsBaseFirst() throws Exception {
        ApkSetValidator.Validation result = ApkSetValidator.validate(Arrays.asList(split("config.arm64"), base()));
        assertEquals(2, result.getApks().size());
        assertEquals("base.apk", result.getApks().get(0).entryName);
        assertEquals(1500L, result.getTotalBytes());
        assertEquals("1.21.100", result.getVersionName());
    }

    @Test
    public void rejectsEmptySet() throws Exception {
        expectInvalid(Collections.emptyList());
    }

    @Test
    public void rejectsMissingBase() throws Exception {
        expectInvalid(Collections.singletonList(split("config.arm64")));
    }

    @Test
    public void rejectsTwoBaseApks() throws Exception {
        expectInvalid(Arrays.asList(base(), new ApkSetValidator.ApkDescriptor(
                "nested/base.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "AABBCC", 1000, true)));
    }

    @Test
    public void rejectsBaseFlagThatDoesNotMatchItsPath() throws Exception {
        ApkSetValidator.ApkDescriptor mislabeled = new ApkSetValidator.ApkDescriptor(
                "splits/config.arm64.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "AABBCC", 500, true);
        expectInvalid(Arrays.asList(mislabeled));
    }

    @Test
    public void rejectsWrongPackage() throws Exception {
        ApkSetValidator.ApkDescriptor wrong = new ApkSetValidator.ApkDescriptor(
                "base.apk", "com.example.fake", "1.21.100", 121100, "AABBCC", 1000, true);
        expectInvalid(Collections.singletonList(wrong));
    }

    @Test
    public void rejectsVersionMismatch() throws Exception {
        ApkSetValidator.ApkDescriptor wrong = new ApkSetValidator.ApkDescriptor(
                "splits/config.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.90", 121090,
                "AABBCC", 500, false);
        expectInvalid(Arrays.asList(base(), wrong));
    }

    @Test
    public void rejectsSignerMismatch() throws Exception {
        ApkSetValidator.ApkDescriptor wrong = new ApkSetValidator.ApkDescriptor(
                "splits/config.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "112233", 500, false);
        expectInvalid(Arrays.asList(base(), wrong));
    }

    @Test
    public void rejectsDuplicateOutputNames() throws Exception {
        ApkSetValidator.ApkDescriptor sameBasenameDifferentFolder = new ApkSetValidator.ApkDescriptor(
                "another/config.arm64.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "AABBCC", 500, false);
        expectInvalid(Arrays.asList(base(), split("config.arm64"), sameBasenameDifferentFolder));
    }

    @Test
    public void rejectsUnsafeArchivePaths() throws Exception {
        for (String path : Arrays.asList("../base.apk", "/base.apk", "splits/../base.apk",
                "splits\\base.apk", "./base.apk")) {
            try {
                ApkSetValidator.validateArchiveEntryName(path);
                fail("Expected unsafe path to be rejected: " + path);
            } catch (ApkSetValidator.ValidationException expected) {
                // expected
            }
        }
    }

    @Test
    public void rejectsUnsupportedSizeAndCount() throws Exception {
        ApkSetValidator.ApkDescriptor huge = new ApkSetValidator.ApkDescriptor(
                "base.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "AABBCC", ApkSetValidator.MAX_SINGLE_APK_BYTES + 1, true);
        expectInvalid(Collections.singletonList(huge));
    }

    @Test
    public void rejectsMissingSignerMetadata() throws Exception {
        ApkSetValidator.ApkDescriptor unsigned = new ApkSetValidator.ApkDescriptor(
                "base.apk", ApkSetValidator.MINECRAFT_PACKAGE, "1.21.100", 121100,
                "", 1000, true);
        expectInvalid(Collections.singletonList(unsigned));
    }

    private void expectInvalid(java.util.List<ApkSetValidator.ApkDescriptor> entries) throws Exception {
        try {
            ApkSetValidator.validate(entries);
            fail("Expected invalid APK set to be rejected");
        } catch (ApkSetValidator.ValidationException expected) {
            // expected
        }
    }
}
