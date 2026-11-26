# Instructions for Claude

## After pushing code that changes app behaviour: deliver it for testing, then test it yourself

The owner tests every change by installing the APK on their phone. Whenever you push new functionality or a bug fix
(anything under `app/` that changes what the app does; not docs, comments or CI-only changes), do all of this without
being asked:

1. **Give the APK link.** Watch the push's "Build APK" run (`.github/workflows/build-apk.yml`; use the GitHub MCP
   `actions_*` tools), and when it succeeds give the owner a link to that run's page with its downloadable artifact
   (`https://github.com/pncodes10/storage-planner/actions/runs/<run id>`). If the run fails, read the job logs, fix it
   and push again; do not hand over a broken build.
2. **Publish a release so the installed app can update itself.** Installed copies read the latest GitHub release
   (`Updater.java`) and update from its signed APK, so create a new tag:
   - bump the version with `tools/release.sh X.Y.Z` (patch bump for fixes, minor for new features; it must be newer
     than the version in `app/build.gradle`; run on the branch being tested with a clean tree). It commits
     "Release X.Y.Z", tags `vX.Y.Z` and pushes branch and tag, which starts the Release workflow
     (`.github/workflows/release.yml`).
   - wait for the Release run to finish and confirm the release `vX.Y.Z` exists with its `.apk` and `.apk.sha256`.
     Give the owner the release link.
   - skip the release (APK link only) when the change is not something to test on the phone, and say so.
3. **Test it yourself on the phone with Phone Pilot** once the release is published. Use the
   `phone-pilot-test` skill (Phone Pilot app on the owner's Android phone: Wi-Fi bridge and Cloudflare tunnel):
   open Storage Planner, use its update check (Settings > Check for updates) and install the new version, confirm the
   installed version is `X.Y.Z`, then exercise the new or fixed functionality, read the app's logs, and fix anything
   that fails (then repeat from step 1 with a new patch version).
   - If Phone Pilot is not connected or the phone is unreachable, say exactly what is blocked and give the owner the
     links so they can test by hand. Do not claim something was tested when it was not.
4. **Report** what you tested and what you saw, including failures, in plain terms.

Notes:
- Releases must be signed with the same key as the installed app (repository secrets `SIGNING_KEY_PEM`,
  `SIGNING_CERT_DER_B64`); if the Release job fails for lack of them, tell the owner instead of working around it.
- Never push to a branch other than the one you were asked to work on; tags are the one addition this file asks for.
- The Mirage model (22 MB) is downloaded by the app on first use (Algorithm Lab box), not packed in the APK; when
  testing Mirage, download it once on the phone first.
