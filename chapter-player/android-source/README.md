# Android integration source for local candidate 172

This directory contains the exact Android Java, menu HTML and native skin/control
sources used for the local 172 integration candidate. `source-manifest.json`
records their hashes and the pinned native build. It is development source,
not a new stable release or evidence of successful Android gameplay.

The local chapter engine is built from TeamFlos/phira commit
`c830dee5e07a2b8f06104576f30d8045e0859aa7` with the sibling chapter-player
patch files and the repository's native build workflow. Its license is GPL-3.0-only;
the compiled artifact and APK include upstream's GPL license.

The Android surface/activity integration adapts Mivik/prpr-miniquad revision
`0c525a3`. Its MIT and Apache license notices are included here. The default
player fonts/resource-pack assets are read from the matching original Phira
0.8.2 APK; its SHA-256 is recorded in the manifest. Skin exports replace only
the validated note images and hold atlas metadata and retain default effects
and sounds. Other copyright/asset licenses are unchanged.

The local modified Phigros APK currently uses the preserved 169 build as its
game-resource base. No private save, key, device log, credential or APK is
included in this source directory. Original Phigros resources are not covered
by Phira's license.

Compile Java with `javac --release 8`, Android 36's `android.jar`, then D8
with minimum API 26. Build the native skin code with the Android ARM64 NDK
compiler and 16KB page alignment. The chapter engine is loaded only in the
non-exported `:chapter_player` Activity process. Permissions and privacy boot
handling remain those of the offline base, with no Internet permission.

Device lifecycle, music, touch, chart formats, chosen skin rendering and
returning to Unity still require real-device testing. The target is an
approximate score/accuracy constrained by chart granularity and explicit rules;
judgment counts generate the result instead of overwriting the final score.
