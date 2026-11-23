#version 150

// Uniform interface rewritten for the LB engine (one std140 UBO instead of individual uniforms).
// Field order matches ClientUniformDefine.SKY_DATA. The neon mode reads uColor1, uColor2 and
// uColor3.x (only .x is used, as a phase tint). Math body byte-identical to the port source.
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

vec3 worldDir() {
    float t = tan(radians(uFov) * 0.5);
    float aspect = uResolution.x / uResolution.y;
    return normalize(uCamRight * (vScreen.x * aspect * t)
                   + uCamUp    * (vScreen.y * t)
                   + uCamForward);
}

// Ported shadertoy Nguyen2007-13, projected onto the sky dome.
// The original builds u = 0.2*(fragCoord+fragCoord-v)/v.y, giving a range ~[-0.2..0.2]*aspect.
// For the sky dome we take a stereographic projection of the view direction onto the plane tangent
// to the zenith: points below us give u -> inf, points on the horizon u ~ 1, the zenith u = 0.
// Multiplied by 0.15 to land in that same small range where 0.5 - dot(u,u) > 0.
void main() {
    vec3 dir = worldDir();

    // Stereographic projection from the bottom pole onto the plane z=1:
    // (x, z) in the plane, y is the "height". Clamp y to avoid dividing by 0 at the bottom point.
    float denom = 1.0 + max(dir.y, -0.95);
    vec2 uv = vec2(dir.x, dir.z) / denom;
    vec2 u = uv * 0.15 * uScale;

    vec2 v = vec2(1.0);
    vec4 z = vec4(uColor1, 0.0);
    vec4 o = vec4(0.0);
    float a = 0.5;
    float t = uTime * uSpeed;

    for (float i = 0.0; i < 19.0; ) {
        i += 1.0;

        vec4 tintPhase = vec4(uColor2, uColor3.x);
        float safeDenom = max(abs(0.5 - dot(u, u)), 0.05);
        o += (1.0 + cos(z + t + tintPhase))
           / length((1.0 + i * dot(v, v))
                    * sin(1.5 * u / safeDenom - 9.0 * u.yx + t));

        t += 1.0;
        v = cos(t - 7.0 * u * pow(a += 0.03, i)) - 5.0 * u;

        float c = cos(i + 0.02 * t - z.w * 11.0);
        float s = sin(i + 0.02 * t - z.w * 11.0);
        mat2 rot = mat2(c, -s, s, c);
        u = u * rot;

        u += tanh(40.0 * dot(u, u) * cos(1e2 * u.yx + t)) / 2e2
           + 0.2 * a * u
           + cos(4.0 / exp(dot(o, o) / 1e2) + t) / 3e2;
    }

    o = 25.6 / (min(o, vec4(13.0)) + 164.0 / max(o, vec4(0.001))) - dot(u, u) / 250.0;

    vec3 col = o.rgb * (0.5 + uIntensity * 40.0);

    float skyDist = mix(FogEnd, FogStart, clamp(dir.y, 0.0, 1.0));
    float fogValue = skyDist <= FogStart ? 0.0
                   : (skyDist < FogEnd ? smoothstep(FogStart, FogEnd, skyDist) : 1.0);
    col = mix(col, FogColor.rgb, fogValue * FogColor.a);

    fragColor = vec4(col, uAlpha);
}
