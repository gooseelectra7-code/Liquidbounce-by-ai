#version 150

// Uniform interface rewritten for the LB engine (one std140 UBO instead of individual uniforms).
// Field order matches ClientUniformDefine.SKY_DATA. Math body byte-identical to the port source.
layout(std140) uniform SkyData {
    vec2 uResolution;
    float uTime;
    float uAlpha;
    float uSpeed;
    float uScale;
    float uIntensity;
    float uFov;
    float FogStart;
    float FogEnd;
    vec3 uColor;
    vec3 uColor1;
    vec3 uColor2;
    vec3 uColor3;
    vec3 uCamRight;
    vec3 uCamUp;
    vec3 uCamForward;
    vec4 FogColor;
};

in vec2 vScreen;

out vec4 fragColor;

float hash3(vec3 p) {
    p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash3(i),                    hash3(i + vec3(1,0,0)), u.x),
                   mix(hash3(i + vec3(0,1,0)),      hash3(i + vec3(1,1,0)), u.x), u.y),
               mix(mix(hash3(i + vec3(0,0,1)),      hash3(i + vec3(1,0,1)), u.x),
                   mix(hash3(i + vec3(0,1,1)),      hash3(i + vec3(1,1,1)), u.x), u.y), u.z);
}

float fbm3(vec3 p) {
    float v = 0.5 * vnoise3(p);          p *= 2.03;
    v +=      0.25 * vnoise3(p);         p *= 2.03;
    v +=      0.125 * vnoise3(p);
    return v;
}

vec3 worldDir() {
    float t = tan(radians(uFov) * 0.5);
    float aspect = uResolution.x / uResolution.y;
    return normalize(uCamRight * (vScreen.x * aspect * t)
                   + uCamUp    * (vScreen.y * t)
                   + uCamForward);
}

vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0/3.0, 2.0/3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + 1.0e-10)),
                d / (q.x + 1.0e-10), q.x);
}

vec3 hsv2rgb(vec3 c) {
    vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
    return c.z * mix(vec3(1.0), rgb, c.y);
}

void main() {
    vec3 dir = worldDir();
    vec3 p = dir * uScale;
    float ts = uTime * uSpeed * 0.3;

    // Single-step domain warp — one fbm instead of four.
    vec3 warp = vec3(ts, ts * 0.7, -ts * 0.5);
    float w = fbm3(p * 0.8 + warp);
    float flow = fbm3(p + w * 3.0);

    vec3 baseHsv = rgb2hsv(uColor);
    float hue = fract(baseHsv.x + (flow - 0.5) * 0.6);
    float sat = clamp(baseHsv.y + 0.3, 0.5, 1.0);
    float val = 0.15 + pow(flow, 1.5) * 1.4;

    vec3 col = hsv2rgb(vec3(hue, sat, val));

    float highlight = pow(smoothstep(0.7, 0.95, flow), 3.0);
    col += vec3(highlight * 0.4);

    col *= (0.5 + uIntensity * 40.0);

    // Vanilla-like linear_fog. dir.y=0 (horizon) -> distance=FogEnd (full fog).
    // dir.y=1 (zenith) -> distance=FogStart (no fog). With a small FogEnd the whole sky is fogged.
    float skyDist = mix(FogEnd, FogStart, clamp(dir.y, 0.0, 1.0));
    float fogValue = skyDist <= FogStart ? 0.0
                   : (skyDist < FogEnd ? smoothstep(FogStart, FogEnd, skyDist) : 1.0);
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);

    fragColor = vec4(col, uAlpha);
}
