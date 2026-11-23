/**
 * Kawase dual-filter blur — downsample pass.
 *
 * 5-tap (center×4 + 4 corners) box average, divisor 8. Pure straight average
 * (no premultiplied-alpha round-trip): the silhouette source FB is binary-alpha
 * solid color, which makes premultiplied and non-premultiplied identical for
 * the input, and any number of straight averages of that input stays
 * mathematically equivalent. We avoid the per-pass `rgb / a` un-premultiply
 * step because in 8-bit framebuffers it produces black halo edges (precision-
 * truncated rgb divided by tiny but-non-zero alpha → vec3(0)).
 *
 * params.xy = 1.0 / destFramebufferSize (texel size in destination UV space)
 * params.z  = user offset scale (wider offset = wider blur per pass)
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D texture0;

layout(std140) uniform KawaseBlurData {
    vec4 params;
};

void main() {
    vec2 hp = params.xy;
    float off = params.z;
    vec2 uv = texCoord;

    vec4 sum = texture(texture0, uv) * 4.0;
    sum += texture(texture0, uv + vec2(-1.0,  1.0) * hp * off);
    sum += texture(texture0, uv + vec2( 1.0,  1.0) * hp * off);
    sum += texture(texture0, uv + vec2( 1.0, -1.0) * hp * off);
    sum += texture(texture0, uv + vec2(-1.0, -1.0) * hp * off);

    fragColor = sum / 8.0;
}
