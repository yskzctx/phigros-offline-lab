# Controls 174 regression tests

The host tests exercise the real planner/dispatcher against a simulated score sink. They do not prove Android gameplay.

For each C test, compile with GCC using `-O2 -std=c11 -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-variable -pthread -lm`, then run the executable. For the three Java tests, compile them together with `chapter-player/android-source/com/phigros/offline/ControlsConfig.java` using `javac --release 8 -encoding UTF-8`, then run the corresponding `com.phigros.offline` class.

Coverage: Perfect priority and goal protection; synthetic Drag/Flick Good; audited 0.65 weighting; Hold-tail and per-type timeout ordering; ordinary/challenge formulas; Early and Late contact on Tap/Hold with normal/tight windows; mixed timing; score/accuracy target planning; native result readback; config bounds and legacy encoding.

The retained 173 regressions cover the unchanged controls. `bad_timing174_test.c` adds early Bad, silent unhit cleanup, long Hold cleanup, clock restoration and timeline ordering. Its original-judge stand-ins do not establish real animation or sound behavior.

`test_fresh_jni.py <build-directory>` builds the production JNI entry into a host library and invokes it in a fresh JVM. It needs Cargo, a host Rust toolchain and a JDK. The old code fails the first valid request; the fixed code additionally checks malformed parameters, path scope, Unicode, inactive rules and retry.

`SkinResources174Test.java`, compiled with SkinPackTest/SkinPack/ChapterSkinExport, checks retained FX/sounds/colors and sound precedence. `ChapterFiles174Test.java`, compiled with ChapterPackTest/ChapterPack/ChapterFiles, checks export roundtrip and scoped deletion. `test_download_check.py` checks same-size corrupt, truncated, missing and valid downloads.

The nine pure chapter planner tests can run with `rustc --edition 2021 --test chapter-player/planner.rs`. The 174 backend was cross compiled locally from pinned upstream; no successful 174 GitHub Actions run is claimed. Android lifecycle, original judgment routines, real frame-order effects, music/touch/rendering and persistence still require a real device.
