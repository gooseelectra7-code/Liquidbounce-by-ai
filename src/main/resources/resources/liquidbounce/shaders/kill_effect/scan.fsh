/**
 * Kill-effect scan wave — port of the NightShift "Scan World" composite.
 *
 * A screen-space pass over the finished scene: every pixel's world position is rebuilt from the
 * depth buffer, and the pixels whose distance to the scan centre falls inside the travelling band
 * [radius - bandWidth, radius] are painted. The band is graded from `innerColor` at its trailing
 * edge through `midColor` to `outerColor` at the leading edge, with a scanline ripple on top, so
 * the wave reads as a shell sliding over the geometry rather than as a flat sphere.
 *
 * Blend is additive, so the wave only ever brightens what is already on screen.
 */
#version 330 core

uniform sampler2D DepthSampler;

layout(std140) uniform ScanWaveData {
    // inverse of projection * modelView for this frame — unprojects straight to camera-relative
    mat4 invProjView;
    // xyz = camera position in world space
    vec4 cameraPos;
    // xyz = scan centre in world space, w = current radius
    vec4 centerRadius;
    vec4 outerColor;
    vec4 midColor;
    vec4 innerColor;
    vec4 scanlineColor;
    // x = width px, y = height px, z = band width, w = time seconds
    vec4 screenData;
    // x = fade (0..1), y = 1 when the device clips depth to [0,1] instead of [-1,1]
    vec4 fadeData;
};

in vec2 texCoord;
out vec4 fragColor;

const float sharpness = 10.0;

float scanlines(float y, float time) {
    return sin(y + time * 10.0) * 0.5 + 0.5;
}

vec3 worldPosFromDepth(float depth) {
    // Minecraft builds its projection with GpuDevice.isZZeroToOne(), so the clip-space z the
    // buffer was written with depends on the backend — remap accordingly instead of assuming GL.
    float z = fadeData.y > 0.5 ? depth : depth * 2.0 - 1.0;
    vec4 clipPos = vec4(texCoord * 2.0 - 1.0, z, 1.0);
    vec4 relative = invProjView * clipPos;
    return cameraPos.xyz + relative.xyz / max(relative.w, 0.0001);
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;

    // the sky carries no geometry for the wave to wash over
    if (depth >= 1.0) {
        discard;
    }

    vec3 worldPos = worldPosFromDepth(depth);
    float radius = centerRadius.w;
    float bandWidth = screenData.z;
    float dist = distance(worldPos, centerRadius.xyz);

    if (dist >= radius || dist <= radius - bandWidth) {
        discard;
    }

    float diff = 1.0 - (radius - dist) / bandWidth;
    vec4 edge = mix(midColor, outerColor, pow(diff, sharpness));
    vec4 color = mix(innerColor, edge, diff);

    color += scanlines(gl_FragCoord.y, screenData.w) * scanlineColor;
    color *= diff;
    color *= fadeData.x;

    fragColor = color;
}
