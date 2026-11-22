#include <assert.h>
#include <stdio.h>
#include <string.h>

#define OFFLINE_HOST_TEST
#include "../src/offline.c"

/*
 * Regression for the protection mode the user actually asked for.
 *
 * The shipped "protection" was autoplay with a rescue: it returned true from the touch hook
 * to inject synthetic presses, so choosing it either played the chart for you or (with input
 * left alone) let a Bad through. The requirement is the opposite: never touch input, and
 * promote a Good/Bad/Miss to Perfect after the game has judged it.
 *
 * These assertions pin three things:
 *   1. promoting() is only true for mode 4 with a kind selected;
 *   2. promote_covers() implements the three kinds correctly;
 *   3. promote_judgment() moves exactly one count from the judged kind to Perfect, and
 *      automatic() returns the original untouched in this mode.
 */

static unsigned char score_obj[0x120];

static void reset_score(void) {
    memset(score_obj, 0, sizeof(score_obj));
}

static int tally(int offset) { return *integer(score_obj, offset); }
static void set_tally(int offset, int value) { *integer(score_obj, offset) = value; }

int main(void) {
    /* ---- 1. promoting() gate --------------------------------------------- */
    atomic_store(&ready, true);
    atomic_store(&enabled, false);          /* tool off: promotion must do nothing */

    chart_config.mode = 3;
    chart_config.assist_guard = 1;
    assert(!promoting());                   /* mode 3 is the planner, not promotion */

    chart_config.mode = 4;
    chart_config.assist_guard = 1;
    assert(!promoting());                   /* auto toggle off -> inactive */

    atomic_store(&enabled, true);           /* auto on: the tool is active */
    chart_config.assist_guard = 0;
    assert(!promoting());                   /* no kind selected */

    chart_config.assist_guard = 1;
    assert(promoting());

    /* ---- 2. protect AP (kind 1): everything non-Perfect is covered --------- */
    reset_score();
    chart_config.mode = 4;
    chart_config.assist_guard = 1;
    assert(!promote_covers(score_obj, 0));  /* Perfect stays */
    assert(promote_covers(score_obj, 1));   /* Good promoted */
    assert(promote_covers(score_obj, 2));   /* Bad promoted */
    assert(promote_covers(score_obj, 3));   /* Miss promoted */

    /* ---- 3. protect FC: only Bad and Miss --------------------------------- */
    chart_config.assist_guard = 2;
    assert(!promote_covers(score_obj, 0));
    assert(!promote_covers(score_obj, 1));  /* Good is allowed under FC */
    assert(promote_covers(score_obj, 2));
    assert(promote_covers(score_obj, 3));

    /* ---- 4. promote_judgment moves one count to Perfect ------------------- */
    reset_score();
    chart_config.assist_guard = 1;
    set_tally(0x5c, 1);                     /* one Good on the board */
    promote_judgment(score_obj, 1);
    assert(tally(0x5c) == 0);               /* Good decremented */
    assert(tally(0x58) == 1);               /* Perfect incremented */

    /* A Miss becomes Perfect the same way. */
    reset_score();
    set_tally(0x64, 1);
    promote_judgment(score_obj, 3);
    assert(tally(0x64) == 0);
    assert(tally(0x58) == 1);

    /* Under protect-FC a Good must be left alone. */
    reset_score();
    chart_config.assist_guard = 2;
    set_tally(0x5c, 1);
    promote_judgment(score_obj, 1);
    assert(tally(0x5c) == 1);
    assert(tally(0x58) == 0);

    /* A count that is already zero must not go negative. */
    reset_score();
    chart_config.assist_guard = 1;
    promote_judgment(score_obj, 2);
    assert(tally(0x60) == 0);
    assert(tally(0x58) == 1);

    /* ---- 5. protect score: rescues Bad/Miss to Good, leaves Good alone ---- */
    reset_score();
    chart_config.assist_guard = 3;
    chart_config.guard_score = 900000;
    /* 1 Perfect of 1 total = 100% - above the floor, so nothing is promoted. */
    set_tally(0x58, 1);
    assert(!promote_covers(score_obj, 1));
    /* Good is never covered under a score floor. */
    assert(!promote_covers(score_obj, 1));
    /* A Miss of 1 total = 0% - below the floor, so it is rescued to Good. */
    reset_score();
    set_tally(0x64, 1);
    assert(promote_covers(score_obj, 3));
    promote_judgment(score_obj, 3);
    assert(tally(0x64) == 0);               /* Miss removed */
    assert(tally(0x5c) == 1);               /* landed in Good, not Perfect */
    assert(tally(0x58) == 0);

    /* ---- 6. no kind means nothing happens --------------------------------- */
    reset_score();
    chart_config.mode = 4;
    chart_config.assist_guard = 0;
    set_tally(0x64, 1);
    promote_judgment(score_obj, 3);
    assert(tally(0x64) == 1);               /* untouched */
    assert(tally(0x58) == 0);

    puts("PASS promotion mode: promotes after judging, covers AP/FC/score, never needs autoplay");
    return 0;
}
