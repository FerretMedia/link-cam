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
uniform bool uClean;            // Read uCleaned, denoise.frag's output, instead of the planes
uniform sampler2D uCleaned;     // Y', Cb, Cr codes as 0..1, at luma resolution

// --- HDR to normal footage ---------------------------------------------------
// shared/model/Hdr is the reference, step for step: undo PQ or HLG to light,
// put 100 nits at 1.0 as Resolve's Rec.709 output does, BT.2020 to BT.709
// primaries, roll luminance off with BT.2390's EETF from 1000 nits, pull
// out-of-gamut colours toward their own grey, then encode with gamma 2.4.

const float SDR_WHITE = 100.0;
const float PQ_PEAK = 0.7518271;    // Hdr.nitsToPq(SOURCE_PEAK_NITS)
const float MAX_LUM = 0.6757916;    // nitsToPq(SDR_WHITE_NITS) / PQ_PEAK
const float KS = 0.5136874;         // 1.5 · MAX_LUM − 0.5
const float KNEE = 0.2785847;       // Hdr.KNEE

vec3 pqToNits(vec3 e) {
    vec3 p = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, 0.0) / (18.8515625 - 18.6875 * p), vec3(1.0 / 0.1593017578125)) * 10000.0;
}

float nitsToPq(float nits) {
    float y = pow(clamp(nits / 10000.0, 0.0, 1.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
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
    float e = min(nitsToPq(x * SDR_WHITE) / PQ_PEAK, 1.0);
    float t = (e - KS) / (1.0 - KS);
    float t2 = t * t;
    float t3 = t2 * t;
    float p = (2.0 * t3 - 3.0 * t2 + 1.0) * KS + (t3 - 2.0 * t2 + t) * (1.0 - KS) + (-2.0 * t3 + 3.0 * t2) * MAX_LUM;
    return min(pqToNits(vec3(p * PQ_PEAK)).x / SDR_WHITE, 1.0);
}

const mat3 BT2020_TO_709 = mat3(
    1.6605, -0.1246, -0.0182,
    -0.5876, 1.1329, -0.1006,
    -0.0728, -0.0083, 1.1187);

vec3 toSdr(vec3 signal) {
    vec3 lin = (uTransfer == 1 ? pqToNits(signal) : hlgToNits(signal)) / SDR_WHITE;
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

// Half a code of noise, fixed per pixel, so the 8-bit display doesn't band
// what the conversion and the noise reduction have made smooth.
float dither() {
    return (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
}

// Tool luma: the source's own Y', expanded to 0 = black, 1 = white.
float luma(vec2 uv) {
    return ((uClean ? texture(uCleaned, uv).r : texture(uY, uv).r) - uOffset.x) * uScale.x;
}

void main() {
    vec2 uv = uCrop.xy + vUv * uCrop.zw;
    vec3 yuv = uClean
        ? texture(uCleaned, uv).rgb
        : vec3(texture(uY, uv).r, texture(uCb, uv).r, texture(uCr, uv).r);
    vec3 rgb = uYuvToRgb * ((yuv - uOffset) * uScale);
    // The tools read the picture as shown, so their levels mean what they do for normal footage.
    float l = (yuv.x - uOffset.x) * uScale.x;
    if (uTransfer != 0) {
        rgb = toSdr(rgb);
        l = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
    }
    if (uTransfer != 0 || uClean) rgb += dither();

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
