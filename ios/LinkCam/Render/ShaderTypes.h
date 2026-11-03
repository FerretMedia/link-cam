// Uniforms shared by Monitor.metal and MonitorRenderer.swift, declared once
// in C so both sides agree on the layout to the byte.
#ifndef ShaderTypes_h
#define ShaderTypes_h

#include <simd/simd.h>

typedef struct {
    matrix_float3x3 yuvToRgb;   // ColorSpec.shaderConstants(), column-major
    vector_float3 offset;
    vector_float3 scale;
    vector_float3 peakColor;
    vector_float4 crop;         // Punch-in: xy = origin, zw = size, in texture space
    vector_float2 lumaTexel;    // One source pixel in texture space, for peaking
    float zebraLevel;           // Normalised luma; above 1.5 means off
    float peakThreshold;        // Sobel magnitude; below 0 means off
    float time;                 // Seconds, to move the zebra stripes
    int transfer;               // Transfer.ordinal: 0 SDR, 1 PQ, 2 HLG
    int falseColor;
    int clean;                  // Read the cleaned texture, the denoise pass's output, instead of the planes
} MonitorUniforms;

typedef struct {
    vector_float2 lumaTexel;
    vector_float2 chromaTexel;
    float keep;                 // NoiseReduction.keep; 0 starts the history afresh
    float noise;                // NoiseReduction.noise: grain size in codes as 0..1
} DenoiseUniforms;

#endif
