# NesaAdmin Android App

## Update-safe builds

This app must always keep `applicationId = "com.otpfetch.admin"` and use the
same release keystore for every published APK. Set these environment variables
before running `assembleRelease`:

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`versionCode` must increase for every release. Do not distribute debug APKs as
updates: debug keys can differ between machines and CI runners, causing Android
to report that the package conflicts with an existing package.

If the currently installed admin APK was signed with a different key, it must
be uninstalled once before installing the new persistent-key release. This is
an Android security restriction and cannot be bypassed by application code.