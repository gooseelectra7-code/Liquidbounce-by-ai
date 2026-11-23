#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 vUv;
in vec4 vColor;
flat in vec2 vSize;
flat in vec4 vRound;
flat in vec2 vSmoothness;

out vec4 fragColor;

// Hawen SDF rounded box with per-corner radii
float roundedBox(vec2 center, vec2 halfSize, vec4 radius) {
    radius.xy = (center.x > 0.0) ? radius.xy : radius.zw;
    radius.x = (center.y > 0.0) ? radius.x : radius.y;

    vec2 q = abs(center) - halfSize + radius.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius.x;
}

void main() {
    vec2 size = max(vSize, vec2(1.0));
    vec2 halfSize = size * 0.5;
    vec2 center = (vUv - vec2(0.5)) * size;

    // vRound = (RT, RB, LT, LB) from hawen convention
    vec4 radius = vRound;

    float d = roundedBox(center, halfSize, radius);

    // Hawen smoothstep with configurable smoothness
    float smoothness1 = vSmoothness.x;
    float smoothness2 = vSmoothness.y;
    float alpha = smoothstep(smoothness1, smoothness2, -d);

    vec4 color = vColor * ColorModulator;
    color.a *= alpha;

    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
