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

vec3 worldDir() {
    float t = tan(radians(uFov) * 0.5);
    float aspect = uResolution.x / uResolution.y;
    return normalize(uCamRight * (vScreen.x * aspect * t)
                   + uCamUp    * (vScreen.y * t)
                   + uCamForward);
}

// A point star inside a lattice cell. dir is split into cells; each cell is hashed to decide
// whether it holds a star, where in the cell it sits, its size and its twinkle phase. Gives real
// point stars rather than a blurred noise gradient.
float starLayer(vec3 dir, float density, float ts) {
    vec3 c = floor(dir);
    vec3 f = fract(dir) - 0.5;

    // Is there a star in this cell (~10-30% of cells depending on density).
    float has = step(1.0 - density, hash3(c));
    if (has < 0.5) return 0.0;

    // Position in the cell (-0.4..0.4), size, phase.
    vec3 pos = vec3(hash3(c + 1.7), hash3(c + 3.1), hash3(c + 5.9)) - 0.5;
    pos *= 0.8;
    float size = 0.02 + 0.06 * hash3(c + 9.3);
    float phase = hash3(c + 11.7) * 6.28318;

    // Twinkle: individual frequency and amplitude.
    float twinkle = 0.4 + 0.6 * (0.5 + 0.5 * sin(ts * (1.5 + hash3(c + 13.1) * 2.5) + phase));

    // Smooth brightness falloff from the cell centre. Works with the angular distance so a star
    // covers several pixels, not only exactly the centre.
    float d = length(f - pos);
    return smoothstep(size, 0.0, d) * twinkle;
}

void main() {
    vec3 dir = worldDir();
    float ts = uTime * uSpeed;

    vec3 col = vec3(0.02, 0.02, 0.05);

    // Milky way.
    float milkyBand = 1.0 - abs(dir.y * 0.7 + dir.x * 0.3 - 0.1);
    milkyBand = pow(clamp(milkyBand, 0.0, 1.0), 8.0);
    float milkyClouds = vnoise3(dir * uScale * 2.0);
    col += vec3(0.15, 0.18, 0.3) * milkyBand * milkyClouds;

    // Three star layers — different densities and scales.
    float small  = starLayer(dir * 90.0, 0.35, ts);
    float medium = starLayer(dir * 45.0, 0.18, ts) * 1.5;
    float big    = starLayer(dir * 22.0, 0.10, ts) * 2.5;

    // Star tint from its position.
    float tint = hash3(floor(dir * 30.0));
    vec3 starColor = mix(uColor * 0.85, uColor * 1.15, tint);

    col += starColor * (small + medium + big);
    col *= (0.6 + uIntensity * 40.0);

    // Vanilla-like linear_fog. dir.y=0 (horizon) -> distance=FogEnd (full fog).
    // dir.y=1 (zenith) -> distance=FogStart (no fog). With a small FogEnd the whole sky is fogged.
    float skyDist = mix(FogEnd, FogStart, clamp(dir.y, 0.0, 1.0));
    float fogValue = skyDist <= FogStart ? 0.0
                   : (skyDist < FogEnd ? smoothstep(FogStart, FogEnd, skyDist) : 1.0);
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);

    fragColor = vec4(col, uAlpha);
}
