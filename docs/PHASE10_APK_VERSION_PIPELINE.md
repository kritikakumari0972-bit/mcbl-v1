# Phase 10 — APK import and version-download integrity

## What changed

### Imported APK and APKS files

- Import now copies selected APK data into a temporary MCBL cache workspace first.
- A single APK must be readable by Android's package parser and identify as `com.mojang.minecraftpe`.
- APKS extraction checks every zip entry path, rejects absolute paths, backslashes, dot components, duplicate entries and duplicate flattened split filenames.
- The accepted APKS layout must contain exactly one `base.apk` entry (at the archive root or nested under a relative folder). Split APKs are flattened into safe private filenames after validation.
- The complete set is checked before a profile is created: all APKs must have the Minecraft package ID, the same version name, the same long version code and the same signing-certificate SHA-256 fingerprint.
- At least one APK in the set must contain `lib/arm64-v8a/libminecraftpe.so`, matching MCBL's 64-bit ARM native runtime build.
- Base APK is normalized to the first position before native-library extraction.
- Limits: 64 APK files, 256 archive entries, 1 GiB per APK and 2 GiB total expanded data.
- A non-empty existing instance directory is never overwritten by import. Existing worlds, settings and profile data remain untouched if a name collision occurs.
- Temporary import files are removed after success or failure.

### Google Play downloads

- A delivery must contain a base APK first and no duplicate output filenames.
- Every downloaded split must be present, non-empty, parse as Minecraft Bedrock and match the base version code, version name and signer.
- Per-file download caps are enforced during normal and resumed downloads.
- A completed-download manifest is written only after the split set validates.
- Manifest paths are restricted to flat APK filenames inside that version's download directory. Partial `.part` files are never shown as finished downloads.
- The manifest records verified on-disk bytes, not only the sizes advertised by the Play delivery response.
- Concurrent imports cannot write the same version profile at the same time; abandoned staging workspaces older than 24 hours are cleaned before a new import.

### Import preview

- APK and APKS preview extraction is size-bounded.
- APKS preview validates relative entry paths and caps archive entry count and expanded size before reading base metadata.

## Automated coverage

- `ApkSetValidatorTest` covers valid split sets, base-first ordering, empty and missing-base sets, multiple bases, wrong package, version mismatch, signer mismatch, duplicate output names, unsafe archive paths, oversized files and missing signer metadata.
- `ApkSetValidatorSmokeTest.java` is run by the secret-free runtime policy GitHub Actions workflow.
- The existing native runtime compatibility smoke tests continue to run in the same workflow.

## Current boundaries

- An imported `.apks` archive must include a literal `base.apk` entry. Bundletool archives that only contain alternative names such as `base-master.apk` are not claimed as supported by this parser yet.
- Google Play delivery download uses the account/device version catalog and native runtime. APKs are not installed as separate Android system apps for every version.
- This phase does not guarantee every historical Bedrock release can run; the inherited native runtime baseline remains Android API 28+, arm64-v8a and Minecraft 1.21.80+.
- Passing the pure-Java smoke checks does not replace a full Android compile, device install, or real Minecraft launch test.
