/**
 * Halo ESP composite — port of the NightShift "Shader ESP" combine pass.
 *
 * texture0 = the raw entity silhouette (vanilla's entity-outline target, full resolution): every
 *            glowing entity is filled with the colour ModuleESP picked for it.
 * texture1 = the same silhouette after a dual-Kawase blur (half resolution), which is what the
 *            outer halo and the inner falloff are read from.
 *
 * The pass paints four things and adds them together:
 *   - the outer halo, blur^falloff * strength, only outside the silhouette;
 *   - the interior fill plus an inner glow that rises towards the silhouette's edges;
 *   - an outline band found by searching the neighbourhood for the nearest texel of the opposite
 *     state (outside / inside / both);
 *   - an optional vertical shimmer sweep, and an optional global pulse.
 *
 * Blend is additive (LIGHTNING: SRC_ALPHA, ONE) and alpha is written as 1, so the shader's rgb is
 * the literal contribution — channels that blow past 1 give the white-hot core on the rim for free.
 */
#version 330 core

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D texture0; // sharp silhouette
uniform sampler2D texture1; // blurred silhouette

layout(std140) uniform EspHaloData {
    // rgb = tint used when params6.x says so, a = overall opacity
    vec4 mainColor;
    // rgb unused, a = 1 when the tint comes from mainColor instead of the silhouette
    vec4 tintMode;
    // texelX, texelY, outlineWidth, outlineMode (0 outside, 1 inside, 2 both)
    vec4 params1;
    // glowStrength, glowFalloff, fillOpacity, innerGlow
    vec4 params2;
    // outlineStrength, outlineWhite, glowEnabled, fillEnabled
    vec4 params3;
    // shimmerT, shimmerEnabled, shimmerWidth, shimmerBrightness
    vec4 params4;
    // pulseAmount, pulseT, saturation, outlineEnabled
    vec4 params5;
};

float maxChan(vec3 c) {
    return max(c.r, max(c.g, c.b));
}

/** The silhouette stores colour * coverage; normalising by the top channel recovers the hue. */
vec3 hueOf(vec3 c) {
    return c / max(maxChan(c), 1e-4);
}

/**
 * How much of a texel the silhouette covers.
 *
 * Vanilla's entity-outline target is cleared to zero and `core/rendertype_outline.fsh` discards
 * every texel whose model texture is fully transparent, writing `vec4(vertexColor.rgb,
 * ColorModulator.a)` everywhere else — and `RenderType.draw` passes a ColorModulator of
 * (1,1,1,1), so alpha is a flat 1.0 inside the silhouette and 0.0 outside it. Alpha is
 * therefore the real mask while brightness only says which colour ModuleESP picked — a dark
 * tint would otherwise read as empty space and cut the model out of its own outline. The
 * colour channels are still folded in for the box source, which writes opaque colour instead.
 */
float coverage(vec4 c) {
    return max(c.a, maxChan(c.rgb));
}

/**
 * Distance in texels to the nearest texel of the opposite state: outside the silhouette that is
 * the nearest filled texel, inside it the nearest empty one. Large when no border is in range.
 */
float edgeDistance(int w, bool inside) {
    vec2 ts = params1.xy;
    float best = 1e6;
    for (int y = -w; y <= w; y++) {
        for (int x = -w; x <= w; x++) {
            vec2 off = vec2(float(x), float(y));
            float m = coverage(textureLod(texture0, texCoord + off * ts, 0.0));
            bool other = inside ? (m < 0.5) : (m >= 0.5);
            if (other) {
                best = min(best, length(off));
            }
        }
    }
    return best;
}

void main() {
    vec4 mask = texture(texture0, texCoord);
    vec4 glowSample = texture(texture1, texCoord);
    vec3 maskS = mask.rgb;
    vec3 glowS = glowSample.rgb;

    float sharp = coverage(mask);
    float blur = clamp(coverage(glowSample), 0.0, 1.0);
    bool inside = sharp >= 0.5;

    // anything past the halo is none of our business
    if (!inside && blur < 0.0015) {
        discard;
    }

    vec3 tint = tintMode.a > 0.5 ? mainColor.rgb : hueOf(inside ? maskS : glowS);
    float grey = dot(tint, vec3(0.2126, 0.7152, 0.0722));
    tint = clamp(mix(vec3(grey), tint, params5.z), 0.0, 1.0);

    float pulse = 1.0 + params5.x * sin(params5.y * 6.2831853);

    // outer halo
    float halo = 0.0;
    if (params3.z > 0.5 && !inside) {
        halo = pow(blur, params2.y) * params2.x;
    }

    // interior fill plus the inner glow that gathers towards the edges
    float fill = 0.0;
    if (params3.w > 0.5 && inside) {
        fill = params2.z + pow(clamp(1.0 - blur, 0.0, 1.0), 1.5) * params2.w;
    }

    // outline band along the silhouette border
    float rim = 0.0;
    int w = int(params1.z + 0.5);
    if (params5.w > 0.5 && w > 0) {
        int mode = int(params1.w + 0.5);
        if (mode == 2 || (mode == 0 && !inside) || (mode == 1 && inside)) {
            float d = edgeDistance(w, inside);
            if (d <= float(w)) {
                rim = (1.0 - d / (float(w) + 1.0)) * params3.x;
            }
        }
    }

    float body = (halo + fill) * pulse;
    vec3 rgb = tint * body;
    rgb += mix(tint, vec3(1.0), clamp(params3.y, 0.0, 1.0)) * rim * pulse;

    // travelling vertical sweep
    if (params4.y > 0.5) {
        float sweep = 1.0 - smoothstep(0.0, params4.z, abs(texCoord.y - params4.x));
        rgb += vec3(sweep * params4.w * max(body, rim));
    }

    rgb *= mainColor.a;

    if (maxChan(rgb) < 0.002) {
        discard;
    }

    fragColor = vec4(rgb, 1.0);
}
