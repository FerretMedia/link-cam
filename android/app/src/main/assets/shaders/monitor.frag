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

// Tool luma: the source's own Y', expanded to 0 = black, 1 = white.
float luma(vec2 uv) {
    return (texture(uY, uv).r - uOffset.x) * uScale.x;
}

void main() {
    vec2 uv = uCrop.xy + vUv * uCrop.zw;
    vec3 yuv = vec3(texture(uY, uv).r, texture(uCb, uv).r, texture(uCr, uv).r);
    float l = (yuv.x - uOffset.x) * uScale.x;
    vec3 rgb = uYuvToRgb * ((yuv - uOffset) * uScale);

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
