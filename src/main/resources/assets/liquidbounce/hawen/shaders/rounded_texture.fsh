#version 150 core

uniform sampler2D textureIn;
uniform vec2 size;
uniform vec4 round;
uniform vec2 smoothness;
uniform float value;
uniform float alpha;

in vec2 vUV;
out vec4 fragColor;

float roundedBox(vec2 center, vec2 halfSize, vec4 radius) {
    radius.xy = (center.x > 0.0) ? radius.xy : radius.zw;
    radius.x  = (center.y > 0.0) ? radius.x : radius.y;

    vec2 q = abs(center) - halfSize + radius.x;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius.x;
}

void main() {
    vec2 tex = vUV * size;
    float dist = roundedBox(tex - (size / 2.0), (size / 2.0) - value, round);
    float smoothedAlpha = 1.0 - smoothstep(smoothness.x, smoothness.y, dist);
    fragColor = vec4(texture(textureIn, vUV).rgb, smoothedAlpha * alpha);
}
