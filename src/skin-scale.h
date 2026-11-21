#ifndef SKIN_SCALE_H
#define SKIN_SCALE_H

/* Pure sizing rule for note skin sprites.
 *
 * Unity's Sprite.Create takes a single pixels-per-unit value, so a sprite's world
 * size is (pixelsW / ppu, pixelsH / ppu). To keep an imported note from changing
 * size, the replacement must never exceed the original note's world footprint on
 * either axis. Solving both constraints gives one uniform scale:
 *
 *     scale = ppu * max(pxW / oldW, pxH / oldH)
 *
 * Using only the width ratio (the previous behaviour) let the height drift with
 * the skin PNG's aspect ratio, which is why imported notes appeared to change
 * size between skins and between note types.
 *
 * Kept as a separate header so it can be unit-tested on the host without any
 * Android or Unity dependency.
 */

typedef struct {
    float scale;      /* pixels-per-unit to pass to Sprite.Create */
    float world_w;    /* resulting world width  (pxW / scale) */
    float world_h;    /* resulting world height (pxH / scale) */
    float old_w;      /* original world width  (oldW / ppu) */
    float old_h;      /* original world height (oldH / ppu) */
} SkinFit;

/* Returns 0 on success, -1 when the inputs cannot produce a usable sprite. */
static inline int skin_fit_contain(float ppu, float old_w, float old_h,
                                   int px_w, int px_h, SkinFit *out) {
    if (!out) return -1;
    if (!(ppu > 0.f) || !(old_w > 0.f) || !(old_h > 0.f)) return -1;
    if (px_w < 1 || px_h < 1) return -1;

    float fit_w = (float)px_w / old_w;
    float fit_h = (float)px_h / old_h;
    float fit = fit_w > fit_h ? fit_w : fit_h;
    float scale = ppu * fit;
    if (!(scale > 0.f)) return -1;

    out->scale = scale;
    out->world_w = (float)px_w / scale;
    out->world_h = (float)px_h / scale;
    out->old_w = old_w / ppu;
    out->old_h = old_h / ppu;
    return 0;
}

#endif
