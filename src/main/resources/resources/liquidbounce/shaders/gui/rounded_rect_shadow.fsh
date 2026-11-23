#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 vUv;
in vec4 vColor;
flat in ivec2 vSize;
flat in ivec2 vParameters;
flat in float vStrokeWidth;

out vec4 fragColor;

// Drop-shadow for a rounded rectangle. Evaluates the SAME signed-distance rounded box as
// rounded_rect.fsh, but instead of a hard fill it outputs a blurred silhouette that fades out
// over `blur` GUI units past the box edge. The caller draws this as an EXPANDED quad (grown by
// `margin = blur + slack` on each side and shifted by the shadow offset) BEHIND the opaque fill,
// so shadow-then-fill is pixel-identical to a single-pass composite.
//
// Vertex attribute reuse (GUI_ROUNDED_RECT format / rounded_rect.vsh):
// - vSize        : the ORIGINAL rect size (w, h) in GUI units -> the SDF box half-extents.
// - vParameters.x: corner radius (all four corners equal).
// - vParameters.y: `margin` — the half-expansion applied per side, so the expanded quad size is
//                  vSize + 2 * margin. vUv (0..1 over the expanded quad) maps back to box-local
//                  coordinates centred on the shadow (= expanded-quad) centre.
// - vStrokeWidth : `blur` — the fade distance past the box edge, in GUI units.
float sdRoundBox(vec2 p, vec2 halfSize, float radius) {
    float r = min(radius, min(halfSize.x, halfSize.y));
    vec2 q = abs(p) - halfSize + vec2(r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 size = max(vec2(vSize), vec2(1.0));
    vec2 halfSize = size * 0.5;
    float margin = max(float(vParameters.y), 0.0);
    vec2 expandedSize = size + 2.0 * vec2(margin);
    vec2 p = (vUv - vec2(0.5)) * expandedSize;
    float radius = max(float(vParameters.x), 0.0);
    float blur = max(vStrokeWidth, 0.0);

    float d = sdRoundBox(p, halfSize, radius);
    float aa = max(fwidth(d), 1e-4);
    float alpha = 1.0 - smoothstep(-aa, blur + aa, d);

    vec4 color = vColor * ColorModulator;
    color.a *= alpha;
    if (color.a <= 0.001) {
        discard;
    }

    fragColor = color;
}
