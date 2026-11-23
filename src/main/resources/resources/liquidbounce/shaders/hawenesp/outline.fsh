/**
 * Hawen-style ESP outline (обводка).
 *
 * Screen-space silhouette outline. The source `texture0` holds solid-colour
 * entity silhouettes (filled boxes) on a transparent background. For every
 * texel *outside* a silhouette we scan a small disk of radius `thickness`
 * (px) for the nearest filled texel; if one is found the fragment becomes part
 * of the outline band, tinted with the silhouette's own colour. Texels *inside*
 * a silhouette are discarded (outline only) unless the optional fill/chams
 * pass is enabled, in which case they are emitted with `fillAlpha`.
 *
 * This is the single-pass equivalent of Hawen's separable grow-and-discard
 * dilation (EntityOutlineShader): grow the silhouette, subtract the interior,
 * what remains is the outline.
 *
 * params.x  = 1.0 / framebufferWidth   (texel size, U)
 * params.y  = 1.0 / framebufferHeight  (texel size, V)
 * params.z  = outline thickness in pixels
 * params.w  = fill (chams) alpha, 0 = outline only
 * params2.x = outline alpha
 * params2.y = coverage threshold (silhouette alpha considered "solid")
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D texture0;

layout(std140) uniform HawenOutlineData {
    vec4 params;
    vec4 params2;
};

const int ANGLES = 16;
const int RINGS = 3;
const float TAU = 6.28318530718;

void main() {
    vec2 texel = params.xy;
    float thickness = max(params.z, 0.0);
    float fillAlpha = clamp(params.w, 0.0, 1.0);
    float outlineAlpha = clamp(params2.x, 0.0, 1.0);
    float threshold = clamp(params2.y, 0.0, 1.0);

    vec4 center = texture(texture0, texCoord);

    // Inside the silhouette → optional chams fill, otherwise nothing.
    if (center.a > threshold) {
        if (fillAlpha <= 0.0) discard;
        fragColor = vec4(center.rgb, center.a * fillAlpha);
        return;
    }

    // Outside → scan a disk for the nearest filled texel to build the band.
    float best = 0.0;
    vec3 col = vec3(0.0);
    for (int r = 1; r <= RINGS; r++) {
        float rad = thickness * (float(r) / float(RINGS));
        for (int a = 0; a < ANGLES; a++) {
            float ang = (float(a) / float(ANGLES)) * TAU;
            vec2 dir = vec2(cos(ang), sin(ang));
            vec4 s = texture(texture0, texCoord + dir * texel * rad);
            if (s.a > best) {
                best = s.a;
                col = s.rgb;
            }
        }
    }

    if (best <= threshold) discard;

    fragColor = vec4(col, outlineAlpha);
}
