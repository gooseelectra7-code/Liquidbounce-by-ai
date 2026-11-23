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
flat in ivec2 vSize;
flat in ivec2 vParameters;
flat in float vStrokeWidth;

out vec4 fragColor;

float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    // Composite the player skin's face + hat overlay (8px cells at (8,8) and (40,8) of a 64px skin).
    vec2 cell = vUv * (8.0 / 64.0);
    vec4 face = texture(Sampler0, vec2(8.0 / 64.0, 8.0 / 64.0) + cell);
    vec4 hat = texture(Sampler0, vec2(40.0 / 64.0, 8.0 / 64.0) + cell);
    vec3 rgb = mix(face.rgb, hat.rgb, hat.a);
    float texA = max(face.a, hat.a);

    // Rounded-corner mask (same SDF as gui/rounded_rect).
    vec2 size = max(vec2(vSize), vec2(1.0));
    vec2 halfSize = size * 0.5;
    vec2 p = (vUv - vec2(0.5)) * size;
    float radius = max(float(vParameters.x), 0.0);
    float d = sdRoundBox(p, halfSize, radius);
    float aa = max(fwidth(d), 1e-4);
    float mask = smoothstep(0.0, -aa, d);

    vec4 color = vec4(rgb, texA) * vColor * ColorModulator;
    color.a *= mask;
    if (color.a <= 0.001) {
        discard;
    }
    fragColor = color;
}
