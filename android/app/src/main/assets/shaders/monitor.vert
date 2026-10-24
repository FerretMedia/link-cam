#version 300 es
// One triangle that covers the viewport; no vertex buffer needed.
// vUv runs 0..1 left to right and top to bottom, matching how the planes are
// uploaded (row 0 is the top of the picture).
out vec2 vUv;

void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = vec2(p.x, 1.0 - p.y);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
