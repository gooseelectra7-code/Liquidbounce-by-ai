/**
 * Hawen-style ESP final composite.
 *
 * `texture0` is the Kawase-blurred silhouette FB (filled colored entity boxes
 * blurred to a soft glow), used for the bloom halo. The blur chain is a
 * straight average of binary-alpha input, so its rgb is silhouette-color *
 * coverage and its a is coverage — equivalent to a premultiplied-alpha blur.
 *
 * `texture1` is the RAW (unblurred) silhouette FB, used for the chams fill.
 * Its rgb is the straight entity colour and its a is the crisp coverage mask
 * (≈1 inside the box, 0 outside), so chams gets sharp edges and the exact
 * entity colour — no blur-dependent fuzz and no `rgb / a` un-premultiply
 * precision artifacts.
 *
 * Output uses TRANSLUCENT_PREMULTIPLIED_ALPHA blend (`ONE, ONE_MINUS_SRC_ALPHA`):
 *   - Halo (bloom): pure additive. Output is `vec4(rgb_contribution, 0.0)` so
 *     the blend equation degenerates to `dst = src + bg`. Precision-zero rgb
 *     adds nothing → no dark rim.
 *   - Chams (inside the sharp silhouette): premultiplied alpha-over. Output is
 *     `vec4(rgb*a, a)` → `dst = rgb*a + bg*(1-a)`.
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D texture0; // blurred silhouette (bloom source)
uniform sampler2D texture1; // sharp silhouette (chams source)

layout(std140) uniform HawenCompositeData {
    // a == 0 → use sampled mask colour instead of this tint (bloom)
    vec4 bloomTint;
    // a == 0 → use sampled mask colour instead of this tint (chams)
    vec4 chamsTint;
    // x = bloomIntensity, y = chamsAlpha, z = chamsThreshold, w = bloomEnabled (>0.5)
    vec4 params;
};

void main() {
    vec4 blurred = texture(texture0, texCoord);
    vec4 sharp   = texture(texture1, texCoord);

    float bloomIntensity = max(params.x, 0.0);
    float chamsAlpha     = clamp(params.y, 0.0, 1.0);
    float chamsThreshold = clamp(params.z, 0.0, 1.0);
    bool  bloomOn        = params.w > 0.5;

    // Inside the CRISP silhouette → chams overlay (sharp box fill, exact colour).
    if (chamsAlpha > 0.0 && sharp.a > chamsThreshold) {
        vec3 base = (chamsTint.a > 0.0) ? chamsTint.rgb : sharp.rgb;
        float a = chamsAlpha * ((chamsTint.a > 0.0) ? chamsTint.a : 1.0);
        if (a <= 0.0) discard;
        fragColor = vec4(base * a, a);
        return;
    }

    // Otherwise → additive bloom halo contribution.
    if (!bloomOn || bloomIntensity <= 0.0) discard;

    vec3 contrib;
    if (bloomTint.a > 0.0) {
        contrib = bloomTint.rgb * blurred.a * bloomIntensity;
    } else {
        // blurred.rgb is silhouette-color * coverage already (premultiplied via averaging).
        contrib = blurred.rgb * bloomIntensity;
    }

    if (max(max(contrib.r, contrib.g), contrib.b) <= 0.001) discard;

    fragColor = vec4(contrib, 0.0);
}
