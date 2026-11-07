// The monitor's shaders: the Metal port of the Android app's monitor.frag and
// denoise.frag, kept step for step the same so both apps show the same
// picture. The numbers they use (false color bands, zebra level, peaking
// threshold) come from the shared module as uniforms and a ramp texture, and
// shared/model/Hdr and shared/tools are the references they're checked against.

#include <metal_stdlib>
#include "ShaderTypes.h"

using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 uv;
};

// One triangle that covers the viewport; no vertex buffer needed. uv runs 0..1
// left to right and top to bottom, matching the pixel buffer's rows (row 0 is
// the top of the picture).
vertex VertexOut fullscreenVertex(uint vid [[vertex_id]]) {
    float2 p = float2(float((vid << 1) & 2), float(vid & 2));
    VertexOut out;
    out.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    out.uv = float2(p.x, 1.0 - p.y);
    return out;
}

constexpr sampler linearClamp(filter::linear, address::clamp_to_edge);
constexpr sampler nearestClamp(filter::nearest, address::clamp_to_edge);

// --- HDR to normal footage ---------------------------------------------------
// shared/model/Hdr is the reference, step for step: undo PQ or HLG to light,
// put 100 nits at 1.0 as Resolve's Rec.709 output does, BT.2020 to BT.709
// primaries, roll luminance off with BT.2390's EETF from 1000 nits, pull
// out-of-gamut colours toward their own grey, then encode with gamma 2.4.

constant float SDR_WHITE = 100.0;
constant float PQ_PEAK = 0.7518271;    // Hdr.nitsToPq(SOURCE_PEAK_NITS)
constant float MAX_LUM = 0.6757916;    // nitsToPq(SDR_WHITE_NITS) / PQ_PEAK
constant float KS = 0.5136874;         // 1.5 · MAX_LUM − 0.5
constant float KNEE = 0.2785847;       // Hdr.KNEE

static float3 pqToNits(float3 e) {
    float3 p = pow(saturate(e), float3(1.0 / 78.84375));
    return pow(max(p - 0.8359375, float3(0.0)) / (18.8515625 - 18.6875 * p), float3(1.0 / 0.1593017578125)) * 10000.0;
}

static float nitsToPq(float nits) {
    float y = pow(saturate(nits / 10000.0), 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
}

static float3 hlgToNits(float3 e) {
    float3 v = saturate(e);
    float3 low = v * v / 3.0;
    float3 high = (exp((v - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    float3 scene = mix(low, high, step(float3(0.5), v));
    float ys = dot(scene, float3(0.2627, 0.6780, 0.0593));
    return scene * 1000.0 * (ys > 0.0 ? pow(ys, 0.2) : 0.0);
}

static float toneMap(float x) {
    if (x <= KNEE) return max(x, 0.0);
    float e = min(nitsToPq(x * SDR_WHITE) / PQ_PEAK, 1.0);
    float t = (e - KS) / (1.0 - KS);
    float t2 = t * t;
    float t3 = t2 * t;
    float p = (2.0 * t3 - 3.0 * t2 + 1.0) * KS + (t3 - 2.0 * t2 + t) * (1.0 - KS) + (-2.0 * t3 + 3.0 * t2) * MAX_LUM;
    return min(pqToNits(float3(p * PQ_PEAK)).x / SDR_WHITE, 1.0);
}

constant float3x3 BT2020_TO_709 = float3x3(
    float3(1.6605, -0.1246, -0.0182),
    float3(-0.5876, 1.1329, -0.1006),
    float3(-0.0728, -0.0083, 1.1187));

static float3 toSdr(float3 signal, int transfer) {
    float3 lin = (transfer == 1 ? pqToNits(signal) : hlgToNits(signal)) / SDR_WHITE;
    lin = BT2020_TO_709 * lin;
    float y = dot(lin, float3(0.2126, 0.7152, 0.0722));
    if (y <= 0.0) return float3(0.0);
    float yt = toneMap(y);
    float3 rgb = lin * (yt / y);
    float hi = max(rgb.r, max(rgb.g, rgb.b));
    float lo = min(rgb.r, min(rgb.g, rgb.b));
    float keep = 1.0;
    if (hi > 1.0) keep = min(keep, (1.0 - yt) / (hi - yt));
    if (lo < 0.0) keep = min(keep, yt / (yt - lo));
    rgb = float3(yt) + (rgb - float3(yt)) * keep;
    return pow(saturate(rgb), float3(1.0 / 2.4));
}

// Half a code of noise, fixed per pixel, so the 8-bit display doesn't band
// what the conversion and the noise reduction have made smooth.
static float dither(float2 fragCoord) {
    return (fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
}

// Tool luma: the source's own Y', expanded to 0 = black, 1 = white.
static float lumaAt(float2 uv, texture2d<float> yTex, texture2d<float> cleaned, constant MonitorUniforms &u) {
    float code = u.clean != 0 ? cleaned.sample(linearClamp, uv).r : yTex.sample(linearClamp, uv).r;
    return (code - u.offset.x) * u.scale.x;
}

// The display pass. Every tool is one step of this shader rather than a pass
// of its own, which saves memory bandwidth.
fragment float4 monitorFragment(VertexOut in [[stage_in]],
                                texture2d<float> yTex [[texture(0)]],
                                texture2d<float> cbcrTex [[texture(1)]],
                                texture2d<float> ramp [[texture(2)]],
                                texture2d<float> cleaned [[texture(3)]],
                                constant MonitorUniforms &u [[buffer(0)]]) {
    float2 uv = u.crop.xy + in.uv * u.crop.zw;
    float3 yuv = u.clean != 0
        ? cleaned.sample(linearClamp, uv).rgb
        : float3(yTex.sample(linearClamp, uv).r, cbcrTex.sample(linearClamp, uv).rg);
    float3 rgb = u.yuvToRgb * ((yuv - u.offset) * u.scale);
    // The tools read the picture as shown, so their levels mean what they do for normal footage.
    float l = (yuv.x - u.offset.x) * u.scale.x;
    if (u.transfer != 0) {
        rgb = toSdr(rgb, u.transfer);
        l = dot(rgb, float3(0.2126, 0.7152, 0.0722));
    }
    if (u.transfer != 0 || u.clean != 0) rgb += dither(in.position.xy);

    if (u.falseColor != 0) {
        float t = saturate(l);
        rgb = ramp.sample(nearestClamp, float2((t * 255.0 + 0.5) / 256.0, 0.5)).rgb;
    }

    if (l >= u.zebraLevel) {
        // Diagonal stripes 6 px wide that crawl, so they can't be mistaken for
        // a pattern in the picture.
        float stripe = step(0.5, fract((in.position.x + in.position.y + u.time * 40.0) / 12.0));
        rgb = mix(rgb, float3(stripe), 0.8);
    }

    if (u.peakThreshold >= 0.0) {
        float2 d = u.lumaTexel;
        float tl = lumaAt(uv + float2(-d.x, -d.y), yTex, cleaned, u);
        float tc = lumaAt(uv + float2(0.0, -d.y), yTex, cleaned, u);
        float tr = lumaAt(uv + float2(d.x, -d.y), yTex, cleaned, u);
        float ml = lumaAt(uv + float2(-d.x, 0.0), yTex, cleaned, u);
        float mr = lumaAt(uv + float2(d.x, 0.0), yTex, cleaned, u);
        float bl = lumaAt(uv + float2(-d.x, d.y), yTex, cleaned, u);
        float bc = lumaAt(uv + float2(0.0, d.y), yTex, cleaned, u);
        float br = lumaAt(uv + float2(d.x, d.y), yTex, cleaned, u);
        float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
        float gy = (bl + 2.0 * bc + br) - (tl + 2.0 * tc + tr);
        if (sqrt(gx * gx + gy * gy) >= u.peakThreshold) {
            rgb = u.peakColor;
        }
    }

    return float4(saturate(rgb), 1.0);
}

// Noise reduction, as a pass of its own ahead of the display pass: it keeps a
// cleaned copy of the picture from frame to frame, in 16-bit float.
//
// Where the picture is still, each new frame is blended into that copy, so the
// grain averages away over several frames while the detail stays sharp. Where
// something moves, the difference from the copy is larger than grain, and the
// pixel takes the new frame through a spatial filter instead, so nothing
// smears. The target is the size of the luma plane and laid out like it.

constant float SPATIAL = 0.22;     // 1/(2σ²) for σ = 1.5 source pixels

fragment float4 denoiseFragment(VertexOut in [[stage_in]],
                                texture2d<float> yTex [[texture(0)]],
                                texture2d<float> cbcrTex [[texture(1)]],
                                texture2d<float> history [[texture(2)]],
                                constant DenoiseUniforms &u [[buffer(0)]]) {
    float2 uv = in.position.xy * u.lumaTexel;
    float centre = yTex.sample(linearClamp, uv).r;

    // A 5×5 bilateral filter on luma: neighbours count by distance and by how
    // close their level is, so grain averages out and edges stay. The middle
    // 3×3 also gives a plain average, to tell movement from grain with.
    float range = 1.0 / (8.0 * u.noise * u.noise); // 1/(2σ²) for σ = twice the grain
    float sum = centre;
    float weight = 1.0;
    float box = centre;
    for (int j = -2; j <= 2; j++) {
        for (int i = -2; i <= 2; i++) {
            if (i == 0 && j == 0) continue;
            float s = yTex.sample(linearClamp, uv + float2(float(i), float(j)) * u.lumaTexel).r;
            float d = s - centre;
            float w = exp(-float(i * i + j * j) * SPATIAL - d * d * range);
            sum += s * w;
            weight += w;
            if (abs(i) <= 1 && abs(j) <= 1) box += s;
        }
    }
    box /= 9.0;

    // Chroma, half resolution and blotchy out of MJPEG: a small cross average.
    float2 d = u.chromaTexel;
    float2 chroma = cbcrTex.sample(linearClamp, uv).rg;
    float2 chromaSmooth = chroma * 2.0;
    chromaSmooth += cbcrTex.sample(linearClamp, uv + float2(d.x, 0.0)).rg;
    chromaSmooth += cbcrTex.sample(linearClamp, uv - float2(d.x, 0.0)).rg;
    chromaSmooth += cbcrTex.sample(linearClamp, uv + float2(0.0, d.y)).rg;
    chromaSmooth += cbcrTex.sample(linearClamp, uv - float2(0.0, d.y)).rg;
    chromaSmooth /= 6.0;

    float3 previous = history.read(uint2(in.position.xy)).rgb;

    // Movement: how far the averaged neighbourhood is from last frame, in grain
    // sizes. The average has a third of the grain, so still pixels sit well
    // under the threshold and moving edges well over it.
    float2 dc = abs(chromaSmooth - previous.yz);
    float change = max(abs(box - previous.x), max(dc.x, dc.y));
    float moving = u.keep > 0.0 ? smoothstep(1.5 * u.noise, 4.0 * u.noise, change) : 1.0;

    float3 sharp = float3(centre, chroma);
    float3 smoothed = float3(sum / weight, chromaSmooth);
    float3 fresh = mix(sharp, smoothed, moving);
    float take = mix(1.0 - u.keep, 1.0, moving);
    // A fresh history is uninitialised memory, which mix() would carry through as NaN.
    return float4(take >= 1.0 ? fresh : mix(previous, fresh, take), 1.0);
}
