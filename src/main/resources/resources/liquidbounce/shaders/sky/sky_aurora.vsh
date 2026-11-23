#version 150

out vec2 vScreen;

void main() {
    // Fullscreen triangle from gl_VertexID (pipeline vertex format is EMPTY, mode TRIANGLES).
    // gl_VertexID 0,1,2 -> (0,0),(2,0),(0,2) -> NDC (-1,-1),(3,-1),(-1,3), covering the screen.
    // The projection is the identity (no matrices bound), so vScreen == the [-1,1] NDC coordinate
    // the fragment shader reconstructs the view ray from — matching the SystemDLC screenquad vsh.
    vec2 pos = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vec2 ndc = pos * 2.0 - 1.0;
    gl_Position = vec4(ndc, 1.0, 1.0);
    vScreen = ndc;
}
