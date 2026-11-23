/**
 * Full-screen saturation post-pass.
 *
 * The luma + mix math is copied verbatim from SystemDLC's `saturation_pass.fsh`
 * (Rec. 601 luma; grayscale at 0, identity at 1, oversaturated above). Only the
 * uniform interface is rewritten for LiquidBounce's client pipeline: a std140 UBO
 * block and the `core/screenquad` `texCoord` varying, exactly the way the sibling
 * `frageffects/desaturate.frag` adapts the same idea.
 *
 *   Saturation = 0  -> full grayscale
 *   Saturation = 1  -> identity (the CPU side dead-bands this out entirely)
 *   Saturation = 2  -> oversaturated (mix extrapolates: 2*rgb - gray)
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D InSampler;

layout(std140) uniform SaturationData {
    // x = saturation (0..2), y..w unused
    vec4 SaturationParams;
};

void main() {
    vec4 color = textureLod(InSampler, texCoord, 0.0);

    // Rec. 601 luma, verbatim from the original.
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 result = mix(vec3(gray), color.rgb, SaturationParams.x);

    // Alpha 1 so the premultiplied blit back into the main target is an exact copy (the
    // FragDesaturate blit-back contract); this equals color.a for the opaque main render target.
    fragColor = vec4(result, 1.0);
}
