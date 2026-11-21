#include <assert.h>
#include <math.h>
#include <stdio.h>
#include "../src/skin-scale.h"

/* Regression for "notes change size after importing a skin".
 *
 * Before the fix the replacement sprite used ppu * pxW / oldW, which keeps the
 * width but lets the height follow the skin PNG's aspect ratio. These cases pin
 * the corrected behaviour: both axes bounded by the original footprint, and a
 * skin with the original aspect ratio reproducing it exactly.
 *
 * Note the units: `world_*` and `old_*` are world units (pixels divided by
 * pixels-per-unit), not pixels.
 */

static void assert_close(float got, float want, const char *label) {
    if (fabsf(got - want) > 1e-4f) {
        printf("FAIL %s: got %.6f want %.6f\n", label, got, want);
        assert(0);
    }
}

int main(void) {
    SkinFit fit;
    const float ppu = 100.f;

    /* 1. Same aspect ratio: the sprite reproduces the original footprint exactly. */
    assert(skin_fit_contain(ppu, 128.f, 32.f, 256, 64, &fit) == 0);
    assert_close(fit.world_w, fit.old_w, "same-aspect width");
    assert_close(fit.world_h, fit.old_h, "same-aspect height");
    assert_close(fit.old_w, 1.28f, "original world width");
    assert_close(fit.old_h, 0.32f, "original world height");

    /* 2. Taller-than-original skin: bounded on both axes. */
    assert(skin_fit_contain(ppu, 128.f, 32.f, 128, 128, &fit) == 0);
    assert(fit.world_w <= fit.old_w + 1e-4f);
    assert(fit.world_h <= fit.old_h + 1e-4f);
    assert_close(fit.world_h, fit.old_h, "tall skin capped to original height");

    /* 3. Wider-than-original skin: bounded on both axes. */
    assert(skin_fit_contain(ppu, 128.f, 32.f, 512, 32, &fit) == 0);
    assert(fit.world_w <= fit.old_w + 1e-4f);
    assert(fit.world_h <= fit.old_h + 1e-4f);
    assert_close(fit.world_w, fit.old_w, "wide skin capped to original width");

    /* 4. The defect itself: a 2:1 skin replacing a 4:1 note used to render with a
     *    doubled height. Confirm the legacy formula violated the bound and the new
     *    one does not. */
    float old_w = 128.f, old_h = 32.f;
    int px_w = 256, px_h = 128;
    float legacy_scale = ppu * (float)px_w / old_w;
    float legacy_h = (float)px_h / legacy_scale;
    assert(legacy_h > old_h / ppu + 1e-4f);       /* old code overshoots */
    assert(skin_fit_contain(ppu, old_w, old_h, px_w, px_h, &fit) == 0);
    assert(fit.world_h <= old_h / ppu + 1e-4f);   /* new code does not */
    assert_close(fit.world_h, old_h / ppu, "2:1 skin bounded by 4:1 note");

    /* 5. All slots of one pack must produce the same scale, otherwise different
     *    note types look differently sized. */
    const int w[] = {256, 256, 256, 256};
    const int h[] = {64, 64, 64, 64};
    const float ow[] = {128.f, 128.f, 128.f, 128.f};
    const float oh[] = {32.f, 32.f, 32.f, 32.f};
    float first = 0.f;
    for (int i = 0; i < 4; i++) {
        assert(skin_fit_contain(ppu, ow[i], oh[i], w[i], h[i], &fit) == 0);
        if (i == 0) first = fit.scale;
        else assert_close(fit.scale, first, "uniform scale across slots");
    }

    /* 6. Hold pieces are cropped to different heights; each must still be bounded
     *    by its own original segment, not by the whole strip. */
    struct { int px_w, px_h; float ow, oh; } pieces[] = {
        {256, 64, 128.f, 32.f},   /* head  */
        {256, 32, 128.f, 16.f},   /* body  */
        {256, 64, 128.f, 32.f},   /* tail  */
    };
    for (int i = 0; i < 3; i++) {
        assert(skin_fit_contain(ppu, pieces[i].ow, pieces[i].oh,
                                pieces[i].px_w, pieces[i].px_h, &fit) == 0);
        assert(fit.world_w <= pieces[i].ow / ppu + 1e-4f);
        assert(fit.world_h <= pieces[i].oh / ppu + 1e-4f);
    }

    /* 7. Degenerate input is rejected instead of producing a zero-size sprite. */
    assert(skin_fit_contain(0.f, 128.f, 32.f, 256, 64, &fit) == -1);
    assert(skin_fit_contain(ppu, 0.f, 32.f, 256, 64, &fit) == -1);
    assert(skin_fit_contain(ppu, 128.f, 0.f, 256, 64, &fit) == -1);
    assert(skin_fit_contain(ppu, 128.f, 32.f, 0, 64, &fit) == -1);
    assert(skin_fit_contain(ppu, 128.f, 32.f, 256, 0, &fit) == -1);
    assert(skin_fit_contain(ppu, 128.f, 32.f, 256, 64, NULL) == -1);

    puts("PASS note skin contain-fit: both axes bounded, same-aspect exact, "
         "uniform across slots, legacy overshoot reproduced and fixed");
    return 0;
}
