#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 vUv;
in vec4 vColor;

out vec4 fragColor;

/**
 * Distance range, in texels, the MSDF atlas was generated with
 * (msdf-atlas-gen `-pxrange`). MUST match the bake setting, otherwise edges
 * are over/under-sharpened.
 */
const float DISTANCE_RANGE = 4.0;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 msd = texture(Sampler0, vUv).rgb;
    float sd = median(msd.r, msd.g, msd.b);

    // Resolution-independent AA: convert the signed distance to screen pixels
    // via the UV derivatives, so text stays crisp at any size/scale.
    vec2 unitRange = vec2(DISTANCE_RANGE) / vec2(textureSize(Sampler0, 0));
    vec2 screenTexSize = vec2(1.0) / fwidth(vUv);
    float screenPxRange = max(0.5 * dot(unitRange, screenTexSize), 1.0);

    float screenDist = screenPxRange * (sd - 0.5);
    float alpha = clamp(screenDist + 0.5, 0.0, 1.0);

    vec4 color = vColor * ColorModulator;
    float outAlpha = color.a * alpha;
    if (outAlpha < 0.001) {
        discard;
    }
    fragColor = vec4(color.rgb, outAlpha);
}
