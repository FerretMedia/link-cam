#version 300 es
// Samples the luma plane on a uGrid-sized grid for the exposure scopes, packing
// four neighbouring samples into each RGBA8 pixel. Read back, the target is
// then exactly uGrid.x × uGrid.y luma codes, row by row from the top of the
// picture, which shared/tools/Scopes bins on the CPU.
//
// texelFetch takes the codes as they are: no filtering, so a clipped highlight
// reads as clipped rather than averaged with its neighbours.
precision highp float;
precision highp int;

uniform highp sampler2D uY;
uniform ivec2 uGrid;

out vec4 outColor;

float sampleAt(int gx, int sy, ivec2 size) {
    int sx = (2 * gx + 1) * size.x / (2 * uGrid.x);
    return texelFetch(uY, ivec2(sx, sy), 0).r;
}

void main() {
    ivec2 size = textureSize(uY, 0);
    ivec2 cell = ivec2(gl_FragCoord.xy);
    int sy = (2 * cell.y + 1) * size.y / (2 * uGrid.y);
    int gx = cell.x * 4;
    outColor = vec4(
        sampleAt(gx, sy, size),
        sampleAt(gx + 1, sy, size),
        sampleAt(gx + 2, sy, size),
        sampleAt(gx + 3, sy, size));
}
