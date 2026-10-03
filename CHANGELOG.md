# Changelog

What changed in each release of Fabric VR. A release's notes on GitHub are its `## X.Y.Z` section,
taken by the organization's publish workflow (`DEC-0104`); the version is `versionName` in
`app/build.gradle.kts` without its `+<sha>`. Nothing has been released yet.

## Unreleased

- **Releases are signed only in CI.** A `vX.Y.Z` tag runs `.github/workflows/release.yml`: after
  approval by `release-approvers`, it builds the APK with the release key, checks it against the
  published certificate (`06:69:C0:CF:…:D5:41`, in full in `docs/deployment/signing.md`), and
  publishes it with a Sigstore attestation and a GPG-signed `SHA256SUMS`. Verify a download with
  `bash scripts/verify-release-apk.sh <apk> <version>` and
  `gh attestation verify <apk> -R passioncode-ai/fabric-vr`.
- A release APK is a different app to a headset that has a debug build installed. Until `T-037`
  ships the migration, installing one there means uninstalling first, which deletes the notes —
  export the vault before trying.
