#version 300 es
// The monitor's display pass. Every tool is one step of this shader rather than
// a pass of its own, which saves memory bandwidth on a phone GPU.
//
// The numbers it uses (false color bands, zebra level, peaking threshold) come
// from the shared module as uniforms and a ramp texture, and the reference
// implementations in shared/tools are what tests check this against.
precision highp float;

in vec2 vUv;
out vec4 outColor;

uniform sampler2D uY;
uniform sampler2D uCb;
uniform sampler2D uCr;
uniform sampler2D uRamp;        // FalseColor.ramp(): 256×1, entry i = i/255 of 0..100 IRE

uniform mat3 uYuvToRgb;         // ColorSpec.shaderConstants()
uniform vec3 uOffset;
uniform vec3 uScale;

uniform vec4 uCrop;             // Punch-in: xy = origin, zw = size, in texture space
uniform vec2 uLumaTexel;        // One source pixel in texture space, for peaking

uniform bool uFalseColor;
uniform float uZebraLevel;      // Normalised luma; above 1.5 means off
uniform float uPeakThreshold;   // Sobel magnitude; below 0 means off
uniform vec3 uPeakColor;
uniform float uTime;            // Seconds, to move the zebra stripes

uniform int uTransfer;          // Transfer.ordinal: 0 SDR, 1 PQ, 2 HLG
uniform float uDenoise;         // NoiseReduction.rangeWeight; 0 is off
uniform vec2 uChromaTexel;      // One chroma sample in texture space

// --- noise reduction ---------------------------------------------------------
// A 5×5 bilateral filter on luma: neighbours count by distance and by how close
// their level is, so grain averages out and edges, which differ by more than
// grain does, stay. Chroma, half resolution and blotchy out of MJPEG, gets a
// small cross average.

const float SPATIAL = 0.22;     // 1/(2σ²) for σ = 1.5 source pixels

float smoothLuma(vec2 uv, float centre) {
    float sum = centre;
    float weight = 1.0;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            if (i == 0 && j == 0) continue;
            float s = texture(uY, uv + vec2(float(i), float(j)) * uLumaTexel).r;
            float d = s - centre;
            float w = exp(-float(i * i + j * j) * SPATIAL - d * d * uDenoise);
            sum += s * w;
            weight += w;
        }
    }
    return sum / weight;
}

vec2 smoothChroma(vec2 uv) {
    vec2 d = uChromaTexel;
    vec2 c = vec2(texture(uCb, uv).r, texture(uCr, uv).r) * 2.0;
    c += vec2(texture(uCb, uv + vec2(d.x, 0.0)).r, texture(uCr, uv + vec2(d.x, 0.0)).r);
    c += vec2(texture(uCb, uv - vec2(d.x, 0.0)).r, texture(uCr, uv - vec2(d.x, 0.0)).r);
    c += vec2(texture(uCb, uv + vec2(0.0, d.y)).r, texture(uCr, uv + vec2(0.0, d.y)).r);
    c += vec2(texture(uCb, uv - vec2(0.0, d.y)).r, texture(uCr, uv - vec2(0.0, d.y)).r);
    return c / 6.0;
}

// --- HDR to normal footage ---------------------------------------------------
// shared/model/Hdr is the reference, step for step: undo PQ or HLG to light,
// put reference white (203 nits) at 1.0, BT.2020 to BT.709 primaries, tone-map
// luminance with a soft shoulder above the knee, pull out-of-gamut colours
// toward their own grey, then encode for an SDR display.

const float KNEE = 0.6;

vec3 pqToNits(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * 10000.0;
}

vec3 hlgToNits(vec3 e) {
    vec3 v = clamp(e, 0.0, 1.0);
    vec3 low = v * v / 3.0;
    vec3 high = (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    vec3 scene = mix(low, high, step(0.5, v));
    float ys = dot(scene, vec3(0.2627, 0.6780, 0.0593));
    return scene * 1000.0 * (ys > 0.0 ? pow(ys, 0.2) : 0.0);
}

float toneMap(float x) {
    if (x <= KNEE) return max(x, 0.0);
    float t = (x - KNEE) / (1.0 - KNEE);
    return KNEE + (1.0 - KNEE) * t / (1.0 + t);
}

const mat3 BT2020_TO_709 = mat3(
    1.6605, -0.1246, -0.0182,
    -0.5876, 1.1329, -0.1006,
    -0.0728, -0.0083, 1.1187);

vec3 toSdr(vec3 signal) {
    vec3 lin = (uTransfer == 1 ? pqToNits(signal) : hlgToNits(signal)) / 203.0;
    lin = BT2020_TO_709 * lin;
    float y = dot(lin, vec3(0.2126, 0.7152, 0.0722));
    if (y <= 0.0) return vec3(0.0);
    float yt = toneMap(y);
    vec3 rgb = lin * (yt / y);
    float hi = max(rgb.r, max(rgb.g, rgb.b));
    float lo = min(rgb.r, min(rgb.g, rgb.b));
    float keep = 1.0;
    if (hi > 1.0) keep = min(keep, (1.0 - yt) / (hi - yt));
    if (lo < 0.0) keep = min(keep, yt / (yt - lo));
    rgb = vec3(yt) + (rgb - vec3(yt)) * keep;
    return pow(clamp(rgb, 0.0, 1.0), vec3(1.0 / 2.4));
}

// Tool luma: the source's own Y', expanded to 0 = black, 1 = white.
float luma(vec2 uv) {
    return (texture(uY, uv).r - uOffset.x) * uScale.x;
}

void main() {
    vec2 uv = uCrop.xy + vUv * uCrop.zw;
    vec3 yuv = vec3(texture(uY, uv).r, texture(uCb, uv).r, texture(uCr, uv).r);
    if (uDenoise > 0.0) {
        yuv.x = smoothLuma(uv, yuv.x);
        yuv.yz = smoothChroma(uv);
    }
    vec3 rgb = uYuvToRgb * ((yuv - uOffset) * uScale);
    // The tools read the picture as shown, so their levels mean what they do for normal footage.
    float l = (yuv.x - uOffset.x) * uScale.x;
    if (uTransfer != 0) {
        rgb = toSdr(rgb);
        l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    }

    if (uFalseColor) {
        float t = clamp(l, 0.0, 1.0);
        rgb = texture(uRamp, vec2((t * 255.0 + 0.5) / 256.0, 0.5)).rgb;
    }

    if (l >= uZebraLevel) {
        // Diagonal stripes 6 px wide that crawl, so they can't be mistaken for
        // a pattern in the picture.
        float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y + uTime * 40.0) / 12.0));
        rgb = mix(rgb, vec3(stripe), 0.8);
    }

    if (uPeakThreshold >= 0.0) {
        vec2 d = uLumaTexel;
        float tl = luma(uv + vec2(-d.x, -d.y));
        float tc = luma(uv + vec2(0.0, -d.y));
        float tr = luma(uv + vec2(d.x, -d.y));
        float ml = luma(uv + vec2(-d.x, 0.0));
        float mr = luma(uv + vec2(d.x, 0.0));
        float bl = luma(uv + vec2(-d.x, d.y));
        float bc = luma(uv + vec2(0.0, d.y));
        float br = luma(uv + vec2(d.x, d.y));
        float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
        float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
        if (sqrt(gx * gx + gy * gy) >= uPeakThreshold) {
            rgb = uPeakColor;
        }
    }

    outColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
