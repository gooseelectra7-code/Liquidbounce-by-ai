#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec2 Size;
in vec2 RoundTR_BR;
in vec3 RoundTL_BL_Smoothness;

out vec2 vUv;
out vec4 vColor;
flat out vec2 vSize;
flat out vec4 vRound;
flat out vec2 vSmoothness;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vUv = UV0;
    vColor = Color;
    vSize = Size;

    // Unpack per-corner radii: hawen uses RT, RB, LT, LB order
    vRound = vec4(RoundTR_BR.x, RoundTR_BR.y, RoundTL_BL_Smoothness.x, RoundTL_BL_Smoothness.y);

    // Smoothness is passed as a constant for now (hawen default: -0.5, 0.5)
    // RoundTL_BL_Smoothness.z is reserved for future use
    vSmoothness = vec2(-0.5, 0.5);
}
