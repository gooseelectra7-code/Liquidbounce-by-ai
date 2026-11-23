/**
 * Replacement for the vanilla `shaders/post/desaturate.json` post-effect, which no longer ships
 * with the game. Blends the scene towards its own luminance and dims it slightly, so a frag reads
 * as the colour draining out of the world rather than as a grey wash.
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D InSampler;

layout(std140) uniform FragDesaturateData {
    // x = strength (0..1), y..w unused
    vec4 FragDesaturateParams;
};

void main() {
    vec4 color = textureLod(InSampler, texCoord, 0.0);
    float strength = clamp(FragDesaturateParams.x, 0.0, 1.0);

    // Rec. 601 luma, which is what the vanilla effect used.
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    vec3 drained = mix(color.rgb, vec3(luma), strength);

    // Crushing the midtones as well is what sells the drain; a pure luma mix alone reads as
    // "slightly washed out" rather than as the colour being pulled out of the world.
    drained = pow(drained, vec3(1.0 + strength * 0.45));
    fragColor = vec4(drained * (1.0 - strength * 0.35), 1.0);
}
