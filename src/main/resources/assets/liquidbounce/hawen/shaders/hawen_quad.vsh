#version 150 core

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

in vec2 Position;
in vec2 UV;

out vec2 vUV;

void main() {
    vUV = UV;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);
}
