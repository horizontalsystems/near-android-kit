# Publishing & wallet integration

The kit publishes via [JitPack](https://jitpack.io), like every other HorizontalSystems
`*-kit-android` library. `jitpack.yml` (`jdk: openjdk17`) and the `maven-publish` block in
`nearkit/build.gradle` mirror `xrp-android`.

JitPack builds the multi-module repo, skips the `app` module, and publishes the library under
the repo-name coordinate:

```
com.github.horizontalsystems:near-android-kit:<commit-or-tag>
```

## Publish steps

1. Push to `horizontalsystems/near-android-kit`.
2. Open `https://jitpack.io/#horizontalsystems/near-android-kit`, look up the commit, or let
   the wallet's first dependency resolution trigger the build.

## Local development

```
./gradlew :nearkit:publishToMavenLocal
```

publishes `com.github.horizontalsystems:near-android-kit:local`. The wallet's
`settings.gradle.kts` already includes `mavenLocal()`, so set `nearKit = "local"` in its
version catalog to consume an unpublished build.

## Wallet-side wiring (`unstoppable-wallet-android`)

`gradle/libs.versions.toml`, under the wallet kits:

```toml
nearKit = "<commit-hash>"
```

and in the kit module list:

```toml
kit-near = { module = "com.github.horizontalsystems:near-android-kit", version.ref = "nearKit" }
```

`walletkit-chain-near/build.gradle.kts`:

```kotlin
api(libs.kit.near)
```

## Service keys

FastNEAR serves balances and history without a key today, but its docs say a key is required.
Pass one as `NearKit.getInstance(..., fastNearApiKey = ...)`. It is sent as a Bearer header to
the FastNEAR REST and transaction APIs only.
