# Security

Report a vulnerability privately to contact@passioncode.ai, never in a public issue. Do not attach
credentials, a working exploit or anyone's notes to an issue or a pull request.

What is in scope: the Fabric VR app itself — where it sends audio and text in the clear
(`NetworkPolicy`, `DEC-0005`), how it stores provider keys (Android Keystore, `SecureSettings`),
the Markdown vault and its export/import, and the build (dependency verification in
`gradle/verification-metadata.xml`, `DEC-0050`). Third-party components in `third_party/` and the
libraries listed in `NOTICE` are reported to their own projects; tell us too if Fabric VR ships the
affected version.
