# Phase 9 — Runtime compatibility and MCBL fork identity

## Runtime work

- Added a single pure-Java compatibility policy used by both the launch entry point and runtime preparation.
- Replaced the old inline version-string parser with numeric component comparison. For example, 1.21.100 compares as newer than 1.21.80 instead of being compared lexicographically.
- The current native Android runtime baseline remains Minecraft 1.21.80 or newer.
- Hard blockers: unsupported old game build, Android below API 28, a non-64-bit process or no arm64-v8a runtime ABI.
- Non-blocking warnings: less than 3 GB total RAM, less than 1 GB currently available RAM, or less than 2 GB free storage.
- Unknown memory/storage measurements are not treated as zero and do not reject the device.
- Runtime preparation checks actual Android memory and free storage before extracting/loading game libraries.
- Added JUnit coverage and a dependency-free javac smoke-test workflow that does not need private app credentials.

## MCBL fork identity

- Android display name is now MCBL in every shipped string locale.
- About text retains LeviMC credit and identifies MCBL as an independent downstream fork.
- About page points to the MCBL repository.
- App update checks point to kritikakumari0972-bit/mcbl-v1, not upstream LeviLauncher releases.
- Application ID remains org.levimc.launcher for now. Changing it without separately configuring the Microsoft/Xbox redirect and associated service identities can break authentication.

## Important limits

This phase improves the real LeviLauncher-derived Bedrock runtime path; it does not bypass platform checks or guarantee launch success on every device. The official licensed Google Play Minecraft package remains required. Multiple versions are managed inside MCBL using its runtime and storage profiles. Android is not asked to install a separate app package for each version.

The native runtime still has a current minimum supported Bedrock version of 1.21.80. Arbitrary historical versions are not promised. The runtime must be extended and tested against each additional game version before that version can be advertised as supported.

## Validation

- BedrockRuntimeCompatibilityTest covers version ordering, minimum game build, API and ABI restrictions, and non-blocking resource warnings.
- BedrockRuntimeCompatibilitySmokeTest.java can be compiled with a plain JDK and is run by .github/workflows/runtime-compatibility.yml.
- A full Android APK build and real-device game launch remain separate validation gates; passing the pure policy tests is not proof that Minecraft starts successfully on a phone.
