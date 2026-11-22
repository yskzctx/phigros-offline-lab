#include <assert.h>
#include <stdio.h>
#include <string.h>
#define OFFLINE_HOST_TEST
#include "../src/offline.c"

/* Regression for the three defects reported against v180:
 *   1. judgment protection (mode 3) was rejected by every entry point
 *   2. the settlement result was never written, so nothing could be uploaded
 *   3. forced-result display must not disturb the read-only path when disabled
 */

static unsigned char score_obj[0x110], level_obj[0x80], list_obj[0x30];
static unsigned char array_obj[0x20 + 300 * 8], notes[300][0x40];
static unsigned char result_obj[0x48], control[0xc0], progress[0xb0];

static void combo(void *s) {
    int c = ++*integer(s, 0x4c);
    if (c > *integer(s, 0x54)) *integer(s, 0x54) = c;
}
static void fake_perfect(void *s, float code, float t, Vec3 p, bool h, const void *m) {
    (void)code; (void)t; (void)p; (void)h; (void)m;
    (*integer(s, 0x58))++; combo(s);
}
static void fake_good(void *s, float code, float t, Vec3 p, bool h, const void *m) {
    (void)code; (void)t; (void)p; (void)h; (void)m;
    (*integer(s, 0x5c))++; combo(s);
}
static void fake_miss(void *s, float code, const void *m) {
    (void)code; (void)m; (*integer(s, 0x64))++; *integer(s, 0x4c) = 0;
}
static void fake_bad(void *s, float code, float t, const void *m) {
    (void)code; (void)t; (void)m; (*integer(s, 0x60))++; *integer(s, 0x4c) = 0;
}
static void *fake_result(void *s, const void *m) {
    (void)m;
    float a = (*integer(s, 0x58) + 0.65f * *integer(s, 0x5c)) / 300.f;
    *integer(result_obj, 0x10) = (int)(900000.f * a + 100000.f * *integer(s, 0x54) / 300.f + 0.5f);
    *number(result_obj, 0x18) = a * 100.f;
    *integer(result_obj, 0x1c) = *integer(s, 0x58);
    *integer(result_obj, 0x20) = *integer(s, 0x5c);
    *integer(result_obj, 0x24) = *integer(s, 0x60);
    *integer(result_obj, 0x28) = *integer(s, 0x64);
    *integer(result_obj, 0x34) = *integer(s, 0x54);
    *integer(result_obj, 0x2c) = 7;
    *integer(result_obj, 0x30) = 9;
    return result_obj;
}

static int record_calls;
static void fake_record(void *s, void *song, void *difficulty, void *result, const void *m) {
    (void)s; (void)song; (void)difficulty; (void)result; (void)m;
    record_calls++;
}

static void setup(ControlConfig c) {
    memset(score_obj, 0, sizeof(score_obj));
    memset(result_obj, 0, sizeof(result_obj));
    plan_last_count = -1; plan_score = NULL;
    memset(control, 0, sizeof(control)); memset(progress, 0, sizeof(progress));
    *(void **)(score_obj + 0x30) = level_obj;
    *(void **)(level_obj + 0x38) = list_obj;
    *(void **)(list_obj + 0x10) = array_obj;
    *integer(list_obj, 0x18) = 300;
    *(size_t *)(array_obj + 0x18) = 300;
    for (int i = 0; i < 300; i++) {
        memset(notes[i], 0, sizeof(notes[i]));
        *(void **)(array_obj + 0x20 + i * 8) = notes[i];
        *number(notes[i], 0x38) = (float)(i + 1);
        *number(notes[i], 0x2c) = 1.f + i / 10.f;
    }
    pending_config = c;
    perfect_original = fake_perfect; good_call = fake_good;
    miss_original = fake_miss; bad_call = fake_bad;
    result_original = fake_result; record_original = fake_record;
    atomic_store(&ready, true); atomic_store(&enabled, true);
    atomic_store(&force_enabled, false);
    record_calls = 0;
}
static void play(void) {
    for (int i = 1; i <= 300; i++) perfect(score_obj, (float)i, 0, (Vec3){1,2,3}, true, NULL);
}

int main(void) {
    /* ---- 1. mode 3 is a valid planning mode -------------------------------- */
    ControlConfig guard = {.mode = 3, .target = 1000000, .accuracy = 10000,
                           .assist_guard = 2, .guard_score = 0};
    setup(guard);
    play();
    /* The protection pipeline must run without producing bad/miss when the
     * target is protect-FC. */
    unsigned char plan[301], types[301];
    memset(types, 1, sizeof(types));
    PlanEstimate est;
    int changed = make_profile_typed(&guard, 300, 4242, plan, &est, types);
    assert(changed == 0);          /* protect-FC leaves no bad/good/miss */
    assert(est.bad == 0 && est.miss == 0);

    /* protect-AP must leave nothing but perfect */
    ControlConfig ap = {.mode = 3, .assist_guard = 3};
    changed = make_profile_typed(&ap, 300, 4242, plan, &est, types);
    assert(changed == 0 && est.perfect == 300);

    /* protect-score must honour the floor */
    ControlConfig floor = {.mode = 3, .assist_guard = 1, .guard_score = 990000};
    make_profile_typed(&floor, 300, 4242, plan, &est, types);
    assert(est.score >= 990000);

    /* ---- 2. record() always persists --------------------------------------- */
    setup(guard);
    atomic_store(&session_dirty, true);   /* an automated session is running */
    record(NULL, NULL, NULL, NULL, NULL);
    assert(record_calls == 1);            /* previously swallowed -> 0 */
    atomic_store(&session_dirty, false);
    record(NULL, NULL, NULL, NULL, NULL);
    assert(record_calls == 2);

    /* ---- 3. forced result: disabled keeps the real values ------------------ */
    setup(guard);
    play();
    void *out = result(score_obj, NULL);
    int real_score = *integer(out, 0x10);
    int real_perfect = *integer(out, 0x1c);
    int real_early = *integer(out, 0x2c);
    assert(real_score > 0 && real_perfect == 300);
    assert(real_early == 7);              /* untouched while disabled */

    /* ---- 4. forced result: enabled overrides every displayed field --------- */
    setup(guard);
    play();
    atomic_store(&force_enabled, true);
    atomic_store(&force_score, 1234567);
    atomic_store(&force_accuracy, 3333);
    atomic_store(&force_perfect, 11);
    atomic_store(&force_good, 22);
    atomic_store(&force_bad, 33);
    atomic_store(&force_miss, 44);
    atomic_store(&force_combo, 55);
    out = result(score_obj, NULL);
    assert(*integer(out, 0x10) == 1234567);
    assert(*number(out, 0x18) > 33.32f && *number(out, 0x18) < 33.34f);
    assert(*integer(out, 0x1c) == 11);
    assert(*integer(out, 0x20) == 22);
    assert(*integer(out, 0x24) == 33);
    assert(*integer(out, 0x28) == 44);
    assert(*integer(out, 0x34) == 55);
    assert(*integer(out, 0x2c) == 0 && *integer(out, 0x30) == 0);

    /* ---- 5. forced result is independent of the autoplay switch ------------ */
    setup(guard);
    play();
    atomic_store(&force_enabled, true);
    atomic_store(&force_score, 777);
    atomic_store(&enabled, false);         /* autoplay off */
    out = result(score_obj, NULL);
    assert(*integer(out, 0x10) == 777);    /* still forced */

    /* ---- 6. protection works with autoplay OFF -----------------------------
     * The module never enables autoplay - it starts the session with
     * changeAutoplay(false) because it must not inject touches into the real game.
     * Every hook used to gate on `enabled`, so on the module the plan was built and
     * then ignored: judgment protection did nothing. These two assertions pin the
     * separation: the plan-driven path runs with autoplay off, while synthetic touch
     * injection stays off. */
    setup(guard);
    play();
    atomic_store(&enabled, false);         /* module: autoplay never turns on */
    assert(active_plan());                 /* plan-driven judgment still runs */
    assert(!active(score_obj));            /* but no touch is injected */
    assert(protect_mode());                /* because mode 3 asks for protection */

    /* A perfect input under a protect-AP plan must be rewritten to Perfect even
     * though autoplay is off, proving the hook body is reachable. protect-AP means
     * every note becomes Perfect, so the Perfect tally must rise and nothing else
     * may. */
    ControlConfig ap_off = {.mode = 3, .assist_guard = 3, .target = 1000000,
                            .accuracy = 10000};
    setup(ap_off);
    play();
    atomic_store(&enabled, false);
    int before_perfect = *integer(score_obj, 0x58);
    int before_other = *integer(score_obj, 0x5c) + *integer(score_obj, 0x60) +
                       *integer(score_obj, 0x64);
    perfect(score_obj, 1.f, 0.f, (Vec3){0.f, 0.f, 0.f}, false, NULL);
    assert(*integer(score_obj, 0x58) == before_perfect + 1);
    assert(*integer(score_obj, 0x5c) + *integer(score_obj, 0x60) +
           *integer(score_obj, 0x64) == before_other);

    puts("PASS guard mode 3 planning, record persistence, forced-result override "
         "and independence from autoplay");
    return 0;
}
