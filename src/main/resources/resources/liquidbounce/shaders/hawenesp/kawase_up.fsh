/**
 * Kawase dual-filter blur — upsample pass.
 *
 * 8-tap (cardinals at ±2 weighted 1, diagonals at ±1 weighted 2, divisor 12).
 * Center is implicit through the diagonals. Used in the expanding half of the
 * pyramid; each upsample doubles the destination resolution and merges in
 * higher-frequency detail held in the larger pyramid level.
 *
 * Like the down pass, this stays in straight-average space (no per-tap
 * premultiply, no per-pass un-premultiply). See `kawase_down.fsh`.
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

    vec4 sum = vec4(0.0);
    sum += texture(texture0, uv + vec2(-2.0,  0.0) * hp * off) * 1.0;
    sum += texture(texture0, uv + vec2(-1.0,  1.0) * hp * off) * 2.0;
    sum += texture(texture0, uv + vec2( 0.0,  2.0) * hp * off) * 1.0;
    sum += texture(texture0, uv + vec2( 1.0,  1.0) * hp * off) * 2.0;
    sum += texture(texture0, uv + vec2( 2.0,  0.0) * hp * off) * 1.0;
    sum += texture(texture0, uv + vec2( 1.0, -1.0) * hp * off) * 2.0;
    sum += texture(texture0, uv + vec2( 0.0, -2.0) * hp * off) * 1.0;
    sum += texture(texture0, uv + vec2(-1.0, -1.0) * hp * off) * 2.0;

    fragColor = sum / 12.0;
}
