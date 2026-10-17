# Controls 173 regression tests

The host tests exercise the real planner/dispatcher against a simulated score sink. They do not prove Android gameplay.

For each C test, compile with GCC using `-O2 -std=c11 -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-variable -pthread -lm`, then run the executable. For the three Java tests, compile them together with `chapter-player/android-source/com/phigros/offline/ControlsConfig.java` using `javac --release 8 -encoding UTF-8`, then run the corresponding `com.phigros.offline` class.

Coverage: Perfect priority and goal protection; synthetic Drag/Flick Good; audited 0.65 weighting; Hold-tail and per-type timeout ordering; ordinary/challenge formulas; Early and Late contact on Tap/Hold with normal/tight windows; mixed timing; score/accuracy target planning; native result readback; config bounds and legacy encoding.

The chapter planner tests run in the pinned ARM64 backend workflow. Android lifecycle, original judgment routines, real frame-order effects, music/touch/rendering and persistence still require a real device.
