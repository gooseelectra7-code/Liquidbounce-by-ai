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

out vec3 vDir;
out vec3 vViewNormal;
out vec3 vViewDir;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vec3 n = normalize(Position);
    vDir = n;
    vViewNormal = normalize((ModelViewMat * vec4(n, 0.0)).xyz);
    vViewDir = -viewPos.xyz;
}
