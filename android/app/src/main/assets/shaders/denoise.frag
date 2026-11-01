#version 300 es
// Noise reduction, as a pass of its own ahead of the display pass: it keeps a
// cleaned copy of the picture from frame to frame, in 16-bit float.
//
// Where the picture is still, each new frame is blended into that copy, so the
// grain averages away over several frames while the detail stays sharp. That
// also gives back the precision 8-bit MJPEG lacks, which matters most in HDR,
// where one 8-bit PQ code in the shadows is a visible step. Where something
// moves, the difference from the copy is larger than grain, and the pixel
// takes the new frame through a spatial filter instead, so nothing smears.
//
// The target is the size of the luma plane and laid out like it, row 0 at the
// top, so the display pass samples it with the same coordinates as the planes.
precision highp float;

out vec4 outColor;

uniform sampler2D uY;
uniform sampler2D uCb;
uniform sampler2D uCr;
uniform sampler2D uHistory;     // Last frame's output: Y', Cb, Cr codes as 0..1
uniform vec2 uLumaTexel;
uniform vec2 uChromaTexel;
uniform float uKeep;            // NoiseReduction.keep; 0 starts the history afresh
uniform float uNoise;           // NoiseReduction.noise: grain size in codes as 0..1

const float SPATIAL = 0.22;     // 1/(2σ²) for σ = 1.5 source pixels

void main() {
    vec2 uv = gl_FragCoord.xy * uLumaTexel;
    float centre = texture(uY, uv).r;

    // A 5×5 bilateral filter on luma: neighbours count by distance and by how
    // close their level is, so grain averages out and edges stay. The middle
    // 3×3 also gives a plain average, to tell movement from grain with.
    float range = 1.0 / (8.0 * uNoise * uNoise); // 1/(2σ²) for σ = twice the grain
    float sum = centre;
    float weight = 1.0;
    float box = centre;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            if (i == 0 && j == 0) continue;
            float s = texture(uY, uv + vec2(float(i), float(j)) * uLumaTexel).r;
            float d = s - centre;
            float w = exp(-float(i * i + j * j) * SPATIAL - d * d * range);
            sum += s * w;
            weight += w;
            if (abs(i) <= 1 && abs(j) <= 1) box += s;
        }
    }
    box /= 9.0;

    // Chroma, half resolution and blotchy out of MJPEG: a small cross average.
    vec2 d = uChromaTexel;
    vec2 chroma = vec2(texture(uCb, uv).r, texture(uCr, uv).r);
    vec2 chromaSmooth = chroma * 2.0;
    chromaSmooth += vec2(texture(uCb, uv + vec2(d.x, 0.0)).r, texture(uCr, uv + vec2(d.x, 0.0)).r);
    chromaSmooth += vec2(texture(uCb, uv - vec2(d.x, 0.0)).r, texture(uCr, uv - vec2(d.x, 0.0)).r);
    chromaSmooth += vec2(texture(uCb, uv + vec2(0.0, d.y)).r, texture(uCr, uv + vec2(0.0, d.y)).r);
    chromaSmooth += vec2(texture(uCb, uv - vec2(0.0, d.y)).r, texture(uCr, uv - vec2(0.0, d.y)).r);
    chromaSmooth /= 6.0;

    vec3 previous = texelFetch(uHistory, ivec2(gl_FragCoord.xy), 0).rgb;

    // Movement: how far the averaged neighbourhood is from last frame, in grain
    // sizes. The average has a third of the grain, so still pixels sit well
    // under the threshold and moving edges well over it.
    vec2 dc = abs(chromaSmooth - previous.yz);
    float change = max(abs(box - previous.x), max(dc.x, dc.y));
    float moving = uKeep > 0.0 ? smoothstep(1.5 * uNoise, 4.0 * uNoise, change) : 1.0;

    vec3 sharp = vec3(centre, chroma);
    vec3 smoothed = vec3(sum / weight, chromaSmooth);
    vec3 fresh = mix(sharp, smoothed, moving);
    float take = mix(1.0 - uKeep, 1.0, moving);
    // A fresh history is uninitialised memory, which mix() would carry through as NaN.
    outColor = vec4(take >= 1.0 ? fresh : mix(previous, fresh, take), 1.0);
}
