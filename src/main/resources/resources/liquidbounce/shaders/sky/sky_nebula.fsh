#version 150

// Uniform interface rewritten for the LB engine: the SystemDLC source declared many individual
// `uniform` floats/vecs; our engine feeds them through one std140 UBO. Field order matches
// ClientUniformDefine.SKY_DATA and the per-frame writeStd140 in ModuleSkyShader. The math body
// below is byte-identical to sky_nebula.fsh from the port source. FogShape (declared but never read
// by any mode in the source) is dropped so all five modes share one block layout.
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

void main() {
    vec3 dir = worldDir();
    vec3 p = dir * uScale;
    float ts = uTime * uSpeed * 0.1;

    vec3 anim = vec3(0.0, ts, 0.0);
    float base = fbm3(p + anim);
    float wave = mix(base, fbm3(p * 1.7 - anim * 1.5), 0.5);

    float highlight = smoothstep(0.55, 0.9, wave) * (uIntensity * 100.0);
    vec3 col = uColor * (0.35 + wave * 0.75) + vec3(highlight);

    // Vanilla-like linear_fog. dir.y=0 (horizon) -> distance=FogEnd (full fog).
    // dir.y=1 (zenith) -> distance=FogStart (no fog). With a small FogEnd the whole sky is fogged.
    float skyDist = mix(FogEnd, FogStart, clamp(dir.y, 0.0, 1.0));
    float fogValue = skyDist <= FogStart ? 0.0
                   : (skyDist < FogEnd ? smoothstep(FogStart, FogEnd, skyDist) : 1.0);
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);

    fragColor = vec4(col, uAlpha);
}
