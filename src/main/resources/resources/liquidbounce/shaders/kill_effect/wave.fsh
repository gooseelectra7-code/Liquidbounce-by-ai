#version 330

layout(std140) uniform u_KillWave {
    // x = progress (0..1), y = time (seconds), z = fadeIn (0..1), w = unused
    vec4 KillWaveParams;
    // base color rgba
    vec4 KillWaveColor;
};

in vec3 vDir;
in vec3 vViewNormal;
in vec3 vViewDir;

out vec4 fragColor;

void main() {
    float progress = KillWaveParams.x;
    float t = KillWaveParams.y;
    float fadeIn = KillWaveParams.z;

    // animated multi-axis ripple based on the unit-sphere direction
    float r1 = sin(vDir.x * 10.0 + t * 6.0);
    float r2 = sin(vDir.y *  8.0 + t * 5.0);
    float r3 = sin(vDir.z *  9.0 + t * 4.5);
    float ripple = (r1 * r2 * r3) * 0.5 + 0.5;

    // ease-out lifetime fade
    float life = max(0.0, 1.0 - progress);
    life = life * life;

    // rim/fresnel-like edge glow: brighter where the shell faces away from the camera
    float ndotv = clamp(abs(dot(vViewNormal, normalize(vViewDir))), 0.0, 1.0);
    float rim = pow(1.0 - ndotv, 1.6);

    float alpha = KillWaveColor.a
        * life
        * fadeIn
        * (0.35 + 0.65 * ripple)
        * (0.35 + 0.85 * rim);

    // brighten color slightly with the rim so the edges read as "glowing"
    vec3 col = KillWaveColor.rgb + vec3(rim * 0.25);

    fragColor = vec4(col, clamp(alpha, 0.0, 1.0));
}
