# Release Checklist

FabiPlayer releases are shipped from `main` after the release candidate passes local checks and a basic device smoke test.

## Versioning

Version values live in `gradle.properties`:

```properties
APP_VERSION_NAME=0.1.0
APP_VERSION_CODE=1
```

For every public release:

1. Update `APP_VERSION_NAME`.
2. Increment `APP_VERSION_CODE`.
3. Move the relevant `CHANGELOG.md` entries from `Unreleased` to the release version.
4. Tag the release as `v<APP_VERSION_NAME>`, for example `v0.1.0`.

## Required Local Checks

Run these before tagging:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:compileDebugKotlin
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:lintDebug
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=false -Ppixelplayer.disableReleaseSigning=true
```

For split APK artifacts, use:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease -Ppixelplayer.enableAbiSplits=true
```

## Signing

Release signing is driven by an untracked `keystore.properties` file at the repository root.

Supported keys:

```properties
storeFile=/absolute/or/repo-relative/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is optional when the release keystore is available as `vz-pixelplay.jks` at the repository root. `storePassword`, `keyAlias`, and `keyPassword` are required for signing. If signing properties or the keystore file are missing, release builds are unsigned. CI workflows create temporary CI signing keys for artifacts; those are not official release keys.

For unsigned verification builds, pass `-Ppixelplayer.disableReleaseSigning=true` even when local signing files exist.

## Device Smoke Test

Install the release candidate and verify:

1. First launch and setup complete.
2. Local library scan finds music.
3. Playback starts, pauses, resumes, skips, and survives backgrounding.
4. Full player opens and closes smoothly.
5. Widget controls still reach the playback service.
6. Navidrome and Jellyfin login screens open.
7. Backup export flow creates a file.

## In-App Updates

The Updates screen (`data/update/`) reads GitHub Releases:

- **Stable:** GitHub Releases (`!prerelease`), with assets matching device ABI.
- **Alpha:** GitHub prereleases tagged `v<X.Y.Z>-alpha.<N>` with assets named `FabiPlayer-<X.Y.Z>-alpha.<N>-<abi>.apk`.

## Retracing Crash Reports

Release builds are minified with R8, so stack traces from the in-app crash reporter are obfuscated. Each alpha release carries its R8 mapping as `FabiPlayer-<X.Y.Z>-alpha.<N>-mapping.txt.gz`.

1. Download the mapping for the exact build that crashed.
2. Save the stack trace to `trace.txt` and run:

```sh
gunzip FabiPlayer-<X.Y.Z>-alpha.<N>-mapping.txt.gz
$ANDROID_HOME/cmdline-tools/latest/bin/retrace FabiPlayer-<X.Y.Z>-alpha.<N>-mapping.txt trace.txt
```

## Publishing

1. Ensure `main` is clean and pushed.
2. Create a tag: `git tag v<APP_VERSION_NAME>`.
3. Push the tag: `git push origin v<APP_VERSION_NAME>`.
4. Create a GitHub release from the tag.
5. Attach APK artifacts and paste the changelog section.
