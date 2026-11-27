// SPDX-License-Identifier: GPL-3.0-or-later
/* Stub libsixel.so.1 for SimpMusic on FuriOS.
 * The system libmpv links libsixel (image output to a terminal), and the real libsixel aborted the
 * JVM with an assertion in sixel_allocator_* when it shared the process with the app. SimpMusic
 * never needs sixel output: this stub has only the 12 functions libmpv imports, and they all
 * return an error, so vo=sixel simply fails to start.
 * Built by update-musette.sh (clang --target=aarch64-linux-gnu, no libc needed). */
#define SIXEL_RUNTIME_ERROR 0x1100
#define EXPORT __attribute__((visibility("default")))
typedef int SIXELSTATUS;

EXPORT SIXELSTATUS sixel_dither_new(void **pp, int n, void *a) { (void)n; (void)a; if (pp) *pp = 0; return SIXEL_RUNTIME_ERROR; }
EXPORT void *sixel_dither_get(int b) { (void)b; return 0; }
EXPORT SIXELSTATUS sixel_dither_initialize(void *d, unsigned char *p, int w, int h, int f, int m, int q, int r) { (void)d; (void)p; (void)w; (void)h; (void)f; (void)m; (void)q; (void)r; return SIXEL_RUNTIME_ERROR; }
EXPORT int sixel_dither_get_num_of_histogram_colors(void *d) { (void)d; return 0; }
EXPORT void sixel_dither_set_body_only(void *d, int b) { (void)d; (void)b; }
EXPORT void sixel_dither_set_diffusion_type(void *d, int t) { (void)d; (void)t; }
EXPORT void sixel_dither_unref(void *d) { (void)d; }
EXPORT SIXELSTATUS sixel_encode(unsigned char *p, int w, int h, int d, void *di, void *o) { (void)p; (void)w; (void)h; (void)d; (void)di; (void)o; return SIXEL_RUNTIME_ERROR; }
EXPORT const char *sixel_helper_format_error(SIXELSTATUS s) { (void)s; return "libsixel disabled (stub)"; }
EXPORT SIXELSTATUS sixel_output_new(void **pp, void *fn, void *priv, void *a) { (void)fn; (void)priv; (void)a; if (pp) *pp = 0; return SIXEL_RUNTIME_ERROR; }
EXPORT void sixel_output_set_encode_policy(void *o, int p) { (void)o; (void)p; }
EXPORT void sixel_output_unref(void *o) { (void)o; }
