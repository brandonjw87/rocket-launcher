package com.brandonwallace.rocketlauncher.gl

/**
 * All GLSL ES 3.00 sources. Every scene shader writes linear HDR multiplied by uOutScale (1.0 on float
 * render targets, 0.25 when the device lacks float colour buffers, so the RGBA8 path still has headroom);
 * the composite pass divides it back out, adds bloom and applies ACES tone mapping + dithering.
 */
object Shaders {
    private const val V = "#version 300 es\n"
    private const val FP = "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DShadow;\n"

    /** Sky-view LUT parametrisation (Hillaire-style: horizon at v = 0.5, extra resolution near it). */
    private const val LUT_FUNCS = """
const float PI = 3.14159265;
uniform float uAltKm;
uniform float uRkm;
uniform float uC0;   // h*(2R+h), computed in double on the CPU for precision
float horizonEl() { return -acos(clamp(uRkm / (uRkm + max(uAltKm, 0.002)), 0.0, 1.0)); }
vec2 lutUV(vec3 d) {
    float el = asin(clamp(d.y, -1.0, 1.0));
    float hor = horizonEl();
    float v;
    if (el >= hor) v = 0.5 + 0.5 * sqrt(clamp((el - hor) / (0.5 * PI - hor), 0.0, 1.0));
    else v = 0.5 - 0.5 * sqrt(clamp((hor - el) / (0.5 * PI + hor), 0.0, 1.0));
    return vec2(atan(d.z, d.x) / (2.0 * PI) + 0.5, v);
}
vec3 lutDir(vec2 uv) {
    float hor = horizonEl();
    float el;
    if (uv.y >= 0.5) { float t = (uv.y - 0.5) * 2.0; el = hor + t * t * (0.5 * PI - hor); }
    else { float t = (0.5 - uv.y) * 2.0; el = hor - t * t * (0.5 * PI + hor); }
    float az = (uv.x - 0.5) * 2.0 * PI;
    return vec3(cos(el) * cos(az), sin(el), cos(el) * sin(az));
}
"""

    private const val NOISE = """
float hash13(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float vnoise3(vec3 p) {
    vec3 i = floor(p); vec3 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    float a = hash13(i), b = hash13(i + vec3(1,0,0)), c = hash13(i + vec3(0,1,0)), d = hash13(i + vec3(1,1,0));
    float e = hash13(i + vec3(0,0,1)), g = hash13(i + vec3(1,0,1)), h = hash13(i + vec3(0,1,1)), k = hash13(i + vec3(1,1,1));
    return mix(mix(mix(a, b, f.x), mix(c, d, f.x), f.y), mix(mix(e, g, f.x), mix(h, k, f.x), f.y), f.z);
}
float vnoise2(vec2 p) {
    vec2 i = floor(p); vec2 f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1,0)), f.x), mix(hash12(i + vec2(0,1)), hash12(i + vec2(1,1)), f.x), f.y);
}
float fbm3(vec3 p, int oct) { float s = 0.0, a = 0.5; for (int i = 0; i < 8; i++) { if (i >= oct) break; s += a * vnoise3(p); p = p * 2.03 + 17.1; a *= 0.5; } return s; }
float fbm2(vec2 p, int oct) { float s = 0.0, a = 0.5; for (int i = 0; i < 8; i++) { if (i >= oct) break; s += a * vnoise2(p); p = p * 2.03 + 17.1; a *= 0.5; } return s; }
"""

    // ------------------------------------------------------------------ PBR lit
    val LIT_VS = V + """
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
layout(location = 2) in vec3 aCol;
layout(location = 3) in vec2 aMat;
uniform mat4 uVP;        // proj * view-rotation (camera sits at the origin: camera-relative rendering)
uniform mat4 uModel;     // camera-relative model matrix
uniform mat3 uNrmMat;
uniform mat4 uShadowVP;
out vec3 vWPos; out vec3 vN; out vec3 vCol; out vec2 vMat; out vec3 vObj; out vec4 vShadow;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWPos = w.xyz;
    vN = normalize(uNrmMat * aNrm);
    vCol = aCol; vMat = aMat; vObj = aPos;
    vShadow = uShadowVP * w;
    gl_Position = uVP * w;
}
"""

    val LIT_FS = V + FP + LUT_FUNCS + NOISE + """
in vec3 vWPos; in vec3 vN; in vec3 vCol; in vec2 vMat; in vec3 vObj; in vec4 vShadow;
out vec4 oColor;
uniform vec3 uSunDir; uniform vec3 uSunCol; uniform vec3 uSkyAmb; uniform vec3 uGroundAmb; uniform vec3 uHorizon;
uniform vec3 uTint; uniform vec3 uEmissive; uniform float uRoughMul; uniform float uAlpha;
uniform int uMatType;          // 0 plain,1 painted/alu body,2 carbon,3 foam,4 stainless,5 tiles,6 terrain,7 engine,8 metal,9 pad,10 gold foil,11 nanotube
uniform vec4 uPanel;           // ring spacing, seam count, extra, paint pattern
uniform sampler2D uDecalTex; uniform vec4 uDecalA; uniform vec4 uDecalB;
uniform sampler2DShadow uShadowMap; uniform float uShadowOn; uniform float uShadowTexel; uniform int uShadowTaps;
uniform sampler2D uLut; uniform float uFogDensity; uniform float uOutScale;
uniform vec3 uFlamePos; uniform vec3 uFlameCol;
uniform vec3 uCamWorld;        // camera position in pad coordinates (for world-space procedural detail)
uniform vec3 uPadRel;          // pad centre, camera-relative
uniform float uTime;

float lineAA(float coord, float spacing, float width) {
    float d = abs(fract(coord / spacing + 0.5) - 0.5) * spacing;
    float w = max(fwidth(coord), 1e-4);
    return 1.0 - smoothstep(width, width + w * 1.5, d);
}

float shadowAt(vec3 n, vec3 l) {
    if (uShadowOn < 0.5) return 1.0;
    vec3 p = vShadow.xyz / vShadow.w * 0.5 + 0.5;
    if (p.x <= 0.0 || p.x >= 1.0 || p.y <= 0.0 || p.y >= 1.0 || p.z >= 1.0) return 1.0;
    float ndl = clamp(dot(n, l), 0.0, 1.0);
    float bias = uShadowTexel * (0.6 + 1.6 * sqrt(1.0 - ndl * ndl));
    float z = p.z - bias;
    float s = 0.0;
    if (uShadowTaps <= 1) return texture(uShadowMap, vec3(p.xy, z));
    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) s += texture(uShadowMap, vec3(p.xy + vec2(float(i), float(j)) * uShadowTexel * 1.25, z));
    return s / 9.0;
}

vec3 envBRDF(vec3 F0, float r, float nv) {
    const vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    const vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 rr = r * c0 + c1;
    float a004 = min(rr.x * rr.x, exp2(-9.28 * nv)) * rr.x + rr.y;
    vec2 AB = vec2(-1.04, 1.04) * a004 + rr.zw;
    return F0 * AB.x + AB.y;
}

vec3 envColor(vec3 r, float rough) {
    vec3 sky = mix(uHorizon, uSkyAmb * 1.4, smoothstep(-0.05, 0.6, r.y));
    vec3 c = mix(uGroundAmb, sky, smoothstep(-0.25, 0.05, r.y));
    return mix(c, (uSkyAmb + uGroundAmb) * 0.5 + uHorizon * 0.3, rough * rough);
}

void main() {
    vec3 albedo = vCol * uTint;
    float rough = vMat.x * uRoughMul;
    float metal = vMat.y;
    vec3 N = normalize(vN);
    if (!gl_FrontFacing) N = -N;
    vec3 emissive = uEmissive;
    float alpha = uAlpha;
    vec3 wp = vWPos + uCamWorld;

    if ((uMatType >= 1 && uMatType <= 5) || uMatType == 11) {
        float ang = atan(vObj.z, vObj.x);
        float rad = length(vObj.xz);
        float arc = ang * rad;
        float y = vObj.y;
        float ring = lineAA(y, uPanel.x, 0.012);
        float seam = uPanel.y > 0.0 ? lineAA(ang * uPanel.y / (2.0 * PI), 1.0, 0.004 * uPanel.y) : 0.0;
        if (uMatType == 1) {
            if (uPanel.w > 0.5) {   // classic black/white roll pattern
                float q = floor((ang + PI) / (0.5 * PI)) + floor(y / (uPanel.x * 2.0));
                albedo *= mod(q, 2.0) < 1.0 ? vec3(1.0) : vec3(0.06);
            }
            float n = vnoise2(vec2(arc, y) * 9.0);
            rough *= 0.85 + 0.3 * n;
        } else if (uMatType == 2) {
            float wv = sin(arc * 140.0) * sin(y * 140.0);
            albedo = vec3(0.035 + 0.02 * step(0.0, wv));
            rough = clamp(0.28 + 0.1 * wv, 0.15, 0.5) * uRoughMul;
            metal = 0.0;
        } else if (uMatType == 3) {
            float n = fbm2(vec2(arc, y) * 5.0, 3);
            albedo = mix(vec3(0.80, 0.36, 0.10), vec3(0.95, 0.52, 0.18), n) * uTint;
            rough = 0.85;
            N = normalize(N + 0.08 * vec3(n - 0.5));
            ring *= 0.4;
        } else if (uMatType == 4) {
            float streak = vnoise2(vec2(arc * 40.0, y * 0.6));
            albedo = vec3(0.74, 0.75, 0.78);
            metal = 1.0;
            rough = (0.16 + 0.14 * streak) * uRoughMul;
        } else if (uMatType == 11) {
            // filament-wound nanotube / nanocomposite skin: crossing helical windings with a satin sheen
            float w = max(lineAA(arc + y * 0.7, 0.11, 0.012), lineAA(arc - y * 0.7, 0.11, 0.012));
            albedo *= 1.0 + 0.45 * w;
            metal = 0.55;
            rough = (0.3 + 0.12 * w) * uRoughMul;
        } else if (uMatType == 5) {
            vec2 tc = vec2(arc, y) / 0.16;
            vec2 f = abs(fract(tc) - 0.5);
            float gap = step(0.44, max(f.x, f.y));
            float v = hash12(floor(tc));
            albedo = mix(vec3(0.03 + 0.03 * v), vec3(0.55, 0.53, 0.5), gap);
            rough = 0.8; metal = 0.0;
        }
        float lines = max(ring, seam);
        albedo *= 1.0 - 0.55 * lines;
        rough = mix(rough, 0.9, lines * 0.5);
        // decal (vertical text / flag / emblem) from the procedural atlas; atan per fragment = no UV seam
        if (uDecalB.y > 0.5) {
            float da = ang - uDecalA.z;
            da = mod(da + PI, 2.0 * PI) - PI;
            vec2 uv = vec2((y - uDecalA.x) / (uDecalA.y - uDecalA.x), 0.5 - da / (2.0 * uDecalA.w));
            if (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0) {
                vec4 t = texture(uDecalTex, vec2(uv.x, (uDecalB.x + uv.y) * 0.25));
                albedo = mix(albedo, t.rgb, t.a);
                rough = mix(rough, 0.45, t.a); metal = mix(metal, 0.0, t.a);
            }
        }
    } else if (uMatType == 6) {
        // terrain: vertex colour = biome albedo, plus world-space detail (no UVs, so no seams)
        float d = length(vWPos);
        float n1 = fbm2(wp.xz * 0.05, 3);
        float n2 = vnoise2(wp.xz * 0.9);
        float fine = mix(n2, 0.5, smoothstep(30.0, 250.0, d));
        albedo *= 0.75 + 0.35 * n1 + 0.25 * (fine - 0.5);
        if (vMat.x < 0.12) {        // water
            vec2 q = wp.xz * 0.08 + vec2(uTime * 0.05, uTime * 0.03);
            vec3 wn = vec3(vnoise2(q * 3.0) - 0.5, 0.0, vnoise2(q * 3.0 + 7.3) - 0.5);
            N = normalize(vec3(0.0, 1.0, 0.0) + wn * mix(0.35, 0.05, smoothstep(50.0, 4000.0, d)));
            rough = 0.06; metal = 0.0;
        }
        float edge = length((vWPos - uPadRel).xz);
        alpha *= 1.0 - smoothstep(15000.0, 22000.0, edge);
    } else if (uMatType == 7) {
        float rad = length(vObj.xz);
        float tubes = uPanel.z > 0.5 ? 0.5 + 0.5 * cos(atan(vObj.z, vObj.x) * 90.0) : 0.5;
        albedo *= 0.8 + 0.4 * tubes;
        float hot = uPanel.x > 0.0 ? smoothstep(uPanel.y, uPanel.x, vObj.y) : 0.0;
        // uEmissive carries the bell glow colour of this engine's exhaust type (orange chemical, violet plasma...)
        vec3 glowCol = dot(uEmissive, uEmissive) > 1e-6 ? uEmissive : vec3(1.0, 0.35, 0.08);
        emissive = glowCol * hot * uDecalB.z;
    } else if (uMatType == 10) {
        // crinkled gold multi-layer insulation: wrinkled normals + streaky reflectance
        float ang = atan(vObj.z, vObj.x);
        vec2 q = vec2(ang * length(vObj.xz), vObj.y);
        float n1 = fbm2(q * 6.0, 3);
        float n2 = vnoise2(q * 23.0);
        N = normalize(N + 0.32 * vec3(n1 - 0.5, n2 - 0.5, n1 - n2));
        albedo *= 0.8 + 0.4 * n1;
        metal = 1.0;
        rough = clamp(0.2 + 0.25 * n2, 0.12, 0.5) * uRoughMul;
    } else if (uMatType == 8) {
        float g = fbm2(wp.xz * 0.7 + wp.y * 0.5, 2);
        albedo *= 0.8 + 0.35 * g;
        rough = clamp(rough + 0.2 * (g - 0.5), 0.1, 1.0);
    } else if (uMatType == 9) {
        // launch pad: concrete slabs, flame trench, safety ring, soot around the mount
        vec2 p = vObj.xz;
        float r = length(p);
        float joints = max(lineAA(p.x, 4.0, 0.02), lineAA(p.y, 4.0, 0.02));
        float slab = hash12(floor(p / 4.0));
        albedo = vec3(0.55, 0.55, 0.53) * (0.88 + 0.18 * slab) * (1.0 - 0.35 * joints);
        float trench = step(abs(p.y), 1.9) * step(abs(p.x), 15.0);
        float grate = lineAA(p.x, 0.5, 0.06);
        albedo = mix(albedo, vec3(0.08) * (1.0 - 0.5 * grate), trench);
        float ringY = 1.0 - smoothstep(0.18, 0.18 + fwidth(r) * 1.5, abs(r - 11.0));
        albedo = mix(albedo, vec3(0.9, 0.7, 0.08), ringY * (1.0 - trench));
        float soot = (1.0 - smoothstep(2.0, 14.0, r)) * (0.6 + 0.4 * fbm2(p * 0.6, 3));
        albedo *= 1.0 - 0.75 * soot;
        rough = 0.85;
    }

    rough = clamp(rough, 0.04, 1.0);
    // geometric specular anti-aliasing: widen highlights where normals vary within a pixel (kills sparkle/flicker)
    vec3 dn = fwidth(N);
    rough = max(rough, min(0.5, length(dn) * 1.2));

    vec3 V = normalize(-vWPos);
    vec3 L = uSunDir;
    vec3 H = normalize(V + L);
    float nl = max(dot(N, L), 0.0);
    float nv = max(dot(N, V), 1e-3);
    float nh = max(dot(N, H), 0.0);
    float vh = max(dot(V, H), 0.0);
    float a = rough * rough;
    float a2 = a * a;
    float dd = nh * nh * (a2 - 1.0) + 1.0;
    float D = a2 / (PI * dd * dd);
    float k = (rough + 1.0) * (rough + 1.0) / 8.0;
    float G = (nv / (nv * (1.0 - k) + k)) * (nl / (nl * (1.0 - k) + k));
    vec3 F0 = mix(vec3(0.04), albedo, metal);
    vec3 F = F0 + (1.0 - F0) * pow(1.0 - vh, 5.0);
    vec3 spec = D * G * F / max(4.0 * nv * nl, 1e-3);
    vec3 kd = (1.0 - F) * (1.0 - metal);
    float sh = shadowAt(N, L);
    vec3 col = (kd * albedo / PI + spec) * uSunCol * nl * sh;

    // ambient: hemisphere diffuse + analytic sky reflection with a mobile EnvBRDF approximation
    vec3 hemi = mix(uGroundAmb, uSkyAmb, N.y * 0.5 + 0.5);
    float ao = 0.75 + 0.25 * sh;
    col += kd * albedo * hemi * ao;
    vec3 R = reflect(-V, N);
    col += envColor(R, rough) * envBRDF(F0, rough, nv) * ao;
    // rim light (sky wrap) for silhouettes
    float rim = pow(1.0 - nv, 4.0);
    col += rim * uHorizon * 0.25 * (0.3 + 0.7 * sh);

    // engine flame point light
    vec3 fl = uFlamePos - vWPos;
    float fd2 = dot(fl, fl);
    col += albedo * uFlameCol * max(dot(N, fl * inversesqrt(fd2 + 1e-4)), 0.0) / (1.0 + fd2 * 0.02);

    col += emissive;

    // aerial perspective: blend toward the actual sky colour in this direction (from the scattering LUT)
    float dist = length(vWPos);
    float T = exp(-dist * uFogDensity);
    vec3 skyc = texture(uLut, lutUV(-V)).rgb * 4.0;
    col = col * T + skyc * (1.0 - T);
    oColor = vec4(col * uOutScale, alpha);
}
"""

    // ------------------------------------------------------------------ shadow depth
    val SHADOW_VS = V + """
layout(location = 0) in vec3 aPos;
uniform mat4 uLightMVP;
void main() { gl_Position = uLightMVP * vec4(aPos, 1.0); }
"""
    val SHADOW_FS = V + FP + "out vec4 o; void main() { o = vec4(1.0); }"

    // ------------------------------------------------------------------ full-screen helpers
    val FULL_VS = V + """
layout(location = 0) in vec2 aPos;
out vec2 vUV; out vec2 vNdc;
void main() { vNdc = aPos; vUV = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    /** Single-scattering atmosphere (Rayleigh + Mie + ozone), ray-marched into a small sky-view LUT each frame. */
    val SKYLUT_FS = V + FP + LUT_FUNCS + """
in vec2 vUV; out vec4 o;
uniform vec3 uSun; uniform float uSunI; uniform int uSteps;
const vec3 BR = vec3(5.802, 13.558, 33.1) * 1e-3;
const float BMS = 3.996e-3; const float BME = 4.44e-3;
const vec3 BO = vec3(0.650, 1.881, 0.085) * 1e-3;
const float TOP = 100.0;
vec3 extinctionAt(float h, out float dR, out float dM) {
    dR = exp(-h / 8.0); dM = exp(-h / 1.2);
    float dO = max(0.0, 1.0 - abs(h - 25.0) / 15.0);
    return BR * dR + BME * dM + BO * dO;
}
vec3 lightDepth(vec3 p) {
    float b = dot(p, uSun);
    float pp = dot(p, p);
    if (b < 0.0 && b * b - (pp - uRkm * uRkm) > 0.0) return vec3(1e4);   // sun below the horizon here
    float c = pp - (uRkm + TOP) * (uRkm + TOP);
    float t = -b + sqrt(max(b * b - c, 0.0));
    vec3 tau = vec3(0.0);
    float dt = t / 4.0;
    for (int i = 0; i < 4; i++) {
        vec3 q = p + uSun * (float(i) + 0.5) * dt;
        float dR, dM;
        tau += extinctionAt(length(q) - uRkm, dR, dM) * dt;
    }
    return tau;
}
void main() {
    vec3 rd = lutDir(vUV);
    float h = max(uAltKm, 0.002);
    vec3 ro = vec3(0.0, uRkm + h, 0.0);
    float b = (uRkm + h) * rd.y;
    float cT = uC0 - TOP * (2.0 * uRkm + TOP);
    float dT = b * b - cT;
    if (dT < 0.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
    float sq = sqrt(dT);
    float t0 = max(0.0, -b - sq);
    float t1 = -b + sq;
    if (t1 <= 0.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
    float dG = b * b - uC0;
    if (b < 0.0 && dG >= 0.0) t1 = min(t1, uC0 / (-b + sqrt(dG)));
    vec3 tau = vec3(0.0), sR = vec3(0.0), sM = vec3(0.0);
    float dt = (t1 - t0) / float(uSteps);
    for (int i = 0; i < 32; i++) {
        if (i >= uSteps) break;
        float t = t0 + (float(i) + 0.5) * dt;
        vec3 p = ro + rd * t;
        float dR, dM;
        vec3 ext = extinctionAt(length(p) - uRkm, dR, dM) * dt;
        vec3 Tv = exp(-(tau + 0.5 * ext));
        vec3 Tl = exp(-lightDepth(p));
        sR += Tv * Tl * dR * dt;
        sM += Tv * Tl * dM * dt;
        tau += ext;
    }
    float mu = dot(rd, uSun);
    float phR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = 0.8;
    float phM = 3.0 / (8.0 * PI) * (1.0 - g * g) * (1.0 + mu * mu) / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * mu, 1.5));
    vec3 L = uSunI * (sR * BR * phR + sM * BMS * phM);
    // approximate higher-order (multiple) scattering: isotropic, sun-shadowed via Tl above.
    // Single scattering alone left the zenith a muddy navy (~20,36,60 sRGB at ground level).
    L += uSunI * 0.25 * (sR * BR + sM * BMS);
    vec3 T = exp(-tau);
    o = vec4(L * 0.25, dot(T, vec3(0.3333)));
}
"""

    /** Full-screen sky: LUT in-scatter over Earth surface / cloud deck / Moon / sun. */
    val SKY_FS = V + FP + LUT_FUNCS + NOISE + """
in vec2 vNdc; out vec4 o;
uniform mat4 uInvVP; uniform sampler2D uLut;
uniform vec3 uSun; uniform float uSunI; uniform vec3 uMoonPos; uniform float uMoonR;
uniform vec3 uMarsDir; uniform float uMarsD;   // Mars: unit direction + centre distance in Mars radii (0 = hidden)
uniform vec3 uJupDir; uniform float uJupD;     // Jupiter: unit direction + centre distance in Jupiter radii (0 = hidden)
uniform vec3 uSatDir; uniform float uSatD;     // Saturn: unit direction + centre distance in equatorial radii (0 = hidden)
uniform vec3 uRingN;                           // Saturn's pole = the ring-plane normal
uniform float uTime; uniform float uDeckKm; uniform float uOutScale; uniform vec2 uPadKm;
// Galilean moons around Jupiter, in Jupiter radii (basis: t1, t2, toward the viewer) and their radii
const vec4 GAL0 = vec4(5.2, 0.4, 2.8, 0.026);      // Io
const vec4 GAL1 = vec4(-8.0, -0.6, 4.9, 0.0224);   // Europa
const vec4 GAL2 = vec4(12.5, 1.0, -8.0, 0.0376);   // Ganymede
const vec4 GAL3 = vec4(-22.0, 1.5, 14.0, 0.0345);  // Callisto
vec3 galColor(int i) { return i == 0 ? vec3(0.85, 0.75, 0.35) : i == 1 ? vec3(0.86, 0.82, 0.74) : i == 2 ? vec3(0.55, 0.5, 0.45) : vec3(0.36, 0.32, 0.28); }
vec4 galData(int i) { return i == 0 ? GAL0 : i == 1 ? GAL1 : i == 2 ? GAL2 : GAL3; }
// Saturn's moons: distance from Saturn in equatorial radii, angle in the ring plane, radius, height above the plane
const vec4 SM0 = vec4(20.27, 0.6, 0.0427, 0.0);    // Titan
const vec4 SM1 = vec4(8.74, 2.1, 0.0127, 0.0);     // Rhea
const vec4 SM2 = vec4(59.1, -1.0, 0.0122, 8.0);    // Iapetus (inclined orbit)
const vec4 SM3 = vec4(6.26, 3.5, 0.0093, 0.0);     // Dione
const vec4 SM4 = vec4(4.89, -2.2, 0.0088, 0.0);    // Tethys
const vec4 SM5 = vec4(3.95, 1.2, 0.0042, 0.0);     // Enceladus
vec4 satMoon(int i) { return i == 0 ? SM0 : i == 1 ? SM1 : i == 2 ? SM2 : i == 3 ? SM3 : i == 4 ? SM4 : SM5; }
vec3 satMoonColor(int i) { return i == 0 ? vec3(0.86, 0.56, 0.24) : i == 2 ? vec3(0.62, 0.58, 0.52) : i == 5 ? vec3(1.0, 1.0, 1.0) : vec3(0.82, 0.8, 0.77); }
/** Saturn's rings: opacity by distance from the centre (equatorial radii): D, C, B, Cassini division, A + Encke gap, F. */
float ringOpacity(float rho, float fw) {
    if (rho < 1.11 || rho > 2.34) return 0.0;
    float o = 0.08 * smoothstep(1.11, 1.13, rho) * (1.0 - smoothstep(1.225, 1.239, rho));
    o += 0.3 * smoothstep(1.236, 1.25, rho) * (1.0 - smoothstep(1.51, 1.527, rho));
    o += 0.92 * smoothstep(1.524, 1.545, rho) * (1.0 - smoothstep(1.94, 1.951, rho));
    o += 0.06 * smoothstep(1.951, 1.96, rho) * (1.0 - smoothstep(2.02, 2.027, rho));
    o += 0.62 * smoothstep(2.025, 2.035, rho) * (1.0 - smoothstep(2.262, 2.269, rho));
    o *= 1.0 - 0.9 * (1.0 - smoothstep(0.0, 0.006, abs(rho - 2.214)));
    o += 0.5 * (1.0 - smoothstep(0.0, 0.005, abs(rho - 2.326)));
    // fine ringlets, faded out where a pixel spans many of them (no shimmering)
    float fine = 0.78 + 0.22 * sin(rho * 220.0) * sin(rho * 57.0 + 1.3) + 0.15 * (vnoise2(vec2(rho * 300.0, 0.5)) - 0.5);
    fine = mix(fine, 0.82, smoothstep(0.002, 0.02, fw));
    return clamp(o * fine, 0.0, 1.0);
}
vec3 ringColor(float rho) {
    vec3 c = mix(vec3(0.45, 0.42, 0.38), vec3(0.86, 0.79, 0.66), smoothstep(1.5, 1.56, rho));
    return mix(c, vec3(0.76, 0.73, 0.68), smoothstep(1.95, 2.03, rho));
}
vec3 cloudColor(float cov) { return vec3(1.0) * (0.75 + 0.25 * cov); }
float cloudCover(vec3 n, float distKm) {
    float big = fbm3(n * 9.0, 4);
    float lod = clamp(1.0 - distKm / 900.0, 0.0, 1.0);
    float mid = fbm3(n * 900.0, 3) * lod;
    float c = big * 0.65 + mid * 0.55 * lod + 0.35 * (1.0 - lod) * big;
    return smoothstep(0.55, 0.8, c);
}
vec3 earthSurface(vec3 hp, vec3 n, float tKm) {
    // continents from fbm; the launch site region (near "up") is forced to land with ocean to the east
    float land = fbm3(n * 2.6 + 3.0, 5);
    float site = 1.0 - smoothstep(0.004, 0.03, length(n.xz));
    float coast = smoothstep(0.4, -0.4, (hp.x - (2.6 + 1.5 * (vnoise2(hp.xz * 0.3) - 0.5))));
    land = mix(land, mix(0.3, 0.75, coast), site);
    bool isLand = land > 0.5;
    float lod = clamp(1.0 - tKm / 600.0, 0.0, 1.0);
    float detail = fbm3(n * 3000.0, 3) * lod + 0.5 * (1.0 - lod);
    float lat = abs(n.z);
    vec3 col;
    if (isLand) {
        vec3 green = vec3(0.10, 0.17, 0.06), brown = vec3(0.32, 0.26, 0.16), sand = vec3(0.55, 0.48, 0.33);
        float dry = fbm3(n * 7.0 + 11.0, 3);
        col = mix(green, brown, smoothstep(0.45, 0.7, dry));
        col = mix(col, sand, smoothstep(0.62, 0.8, dry) * 0.7);
        col *= 0.75 + 0.5 * detail;
        col = mix(col, vec3(0.9), smoothstep(0.82, 0.9, lat));
    } else {
        col = vec3(0.012, 0.045, 0.09) * (0.85 + 0.3 * detail);
        col = mix(col, vec3(0.85), smoothstep(0.86, 0.93, lat));
    }
    float nl = max(dot(n, uSun), 0.0);
    vec3 Tsun = exp(-(vec3(5.802, 13.558, 33.1) * 8e-3 + 4.4e-3 * 1.2) / max(nl, 0.05));
    vec3 c = col / PI * uSunI * nl * Tsun;
    if (!isLand) {   // ocean sun glint
        vec3 v = normalize(-hp);
        vec3 h = normalize(uSun + v);
        c += uSunI * 0.25 * pow(max(dot(n, h), 0.0), 600.0) * Tsun;
    }
    // clouds seen from orbit
    if (uAltKm > 20.0) {
        float cc = cloudCover(n, tKm);
        c = mix(c, cloudColor(cc) / PI * uSunI * nl * Tsun, cc * smoothstep(20.0, 60.0, uAltKm));
    }
    return c;
}
vec3 bodyLight(vec3 dirToBody) {
    vec3 sp = uSun - dirToBody * dot(uSun, dirToBody);
    float sl = length(sp);
    vec3 side = sl > 1e-3 ? sp / sl : vec3(1.0, 0.0, 0.0);
    return normalize(side * 0.66 - dirToBody * 0.75);
}
void main() {
    vec4 p = uInvVP * vec4(vNdc, 1.0, 1.0);
    vec3 rd = normalize(p.xyz / p.w);
    vec4 lut = texture(uLut, lutUV(rd));
    vec3 ins = lut.rgb * 4.0;
    float T = lut.a;
    float h = max(uAltKm, 0.002);
    vec3 ro = vec3(0.0, uRkm + h, 0.0);
    float b = (uRkm + h) * rd.y;
    float dG = b * b - uC0;
    vec3 bg = vec3(0.0);
    float tHit = 1e9;
    if (b < 0.0 && dG >= 0.0) {
        float tG = uC0 / (-b + sqrt(dG));     // numerically stable near-root
        tHit = tG;
        vec3 hp = ro + rd * tG;
        bg = earthSurface(vec3(hp.x, hp.y - uRkm, hp.z), normalize(hp), tG);
    } else {
        float mu = dot(rd, uSun);
        bg += uSunI * 60.0 * smoothstep(0.99997, 0.999985, mu);   // sun disc (blooms in HDR)
        // Moon
        float mb = -dot(uMoonPos, rd);
        float mc = dot(uMoonPos, uMoonPos) - uMoonR * uMoonR;
        float md = mb * mb - mc;
        if (uMoonR > 0.0 && md > 0.0 && -mb > 0.0) {
            float tm = -mb - sqrt(md);
            vec3 mn = normalize(rd * tm - uMoonPos);
            float maria = smoothstep(0.45, 0.65, fbm3(mn * 2.2 + 5.0, 4));
            float crater = 0.0;
            for (int i = 0; i < 3; i++) {
                vec3 q = mn * (6.0 + float(i) * 9.0);
                vec3 cell = floor(q); vec3 f = fract(q) - 0.5;
                float r = hash13(cell) * 0.35;
                float d = length(f - (vec3(hash13(cell + 1.3), hash13(cell + 2.1), hash13(cell + 3.7)) - 0.5) * 0.3);
                crater += (smoothstep(r, r * 0.7, d) * 0.4 - smoothstep(r * 1.15, r, d) * 0.25) * step(0.55, hash13(cell + 9.0));
            }
            float alb = (0.16 - 0.07 * maria) * (1.0 + crater) * (0.9 + 0.2 * fbm3(mn * 40.0, 3));
            float nl = max(dot(mn, bodyLight(normalize(uMoonPos))), 0.0);
            bg = vec3(alb) * uSunI * 1.6 * nl + vec3(0.002, 0.003, 0.005);
            tHit = tm;
        }
        // Mars. Ray/sphere in units of Mars radii using the impact parameter (|rd x dir| * D) so it stays
        // precise in float at interplanetary range, where |p|^2 - r^2 would cancel catastrophically.
        if (uMarsD > 0.0) {
            const float MARS_RKM = 3389.5;
            float ca = dot(rd, uMarsDir);
            vec3 cr = cross(rd, uMarsDir);
            float s2 = dot(cr, cr);
            float p2 = s2 * uMarsD * uMarsD;
            float tm = uMarsD * ca - sqrt(max(1.0 - p2, 0.0));
            if (ca > 0.0 && p2 < 1.0 && tm * MARS_RKM < tHit) {
                vec3 mn = normalize(rd * tm - uMarsDir * uMarsD);
                float big = fbm3(mn * 2.4 + 11.0, 5);
                float fine = fbm3(mn * 22.0 + 3.0, 4);
                vec3 rust = vec3(0.56, 0.25, 0.11), dark = vec3(0.24, 0.12, 0.07), ochre = vec3(0.72, 0.47, 0.27);
                vec3 alb = mix(rust, dark, smoothstep(0.52, 0.70, big));
                alb = mix(alb, ochre, smoothstep(0.60, 0.80, fine) * 0.45);
                alb *= 0.78 + 0.44 * fine;
                // craters (same cell trick as the Moon, sparser and softer)
                vec3 q = mn * 14.0; vec3 cell = floor(q); vec3 fq = fract(q) - 0.5;
                float rr = hash13(cell) * 0.33;
                float dq = length(fq - (vec3(hash13(cell + 1.3), hash13(cell + 2.1), hash13(cell + 3.7)) - 0.5) * 0.3);
                alb *= 1.0 + (smoothstep(rr, rr * 0.7, dq) * 0.25 - smoothstep(rr * 1.15, rr, dq) * 0.2) * step(0.6, hash13(cell + 9.0));
                alb = mix(alb, vec3(0.92, 0.9, 0.87), smoothstep(0.90, 0.95, abs(mn.z)));   // polar caps
                vec3 ml = bodyLight(uMarsDir);
                float nl = max(dot(mn, ml), 0.0);
                float view = max(dot(mn, -rd), 0.0);
                vec3 haze = vec3(0.95, 0.58, 0.38);
                vec3 c = alb / PI * uSunI * 1.35 * nl;
                c = mix(c, haze * uSunI * 0.05 * smoothstep(-0.15, 0.35, dot(mn, ml)), pow(1.0 - view, 4.0) * 0.8);
                bg = c;
                tHit = tm * MARS_RKM;
            } else if (ca > 0.0) {
                float pr = sqrt(p2);
                // thin dusty atmosphere halo just outside the limb (sunlit side)
                // (also used on Jupiter flights: Mars sweeps past during the flyby)
                if (pr < 1.05) {
                    vec3 limbN = normalize(rd * (uMarsD * ca) - uMarsDir * uMarsD);
                    bg += vec3(0.95, 0.58, 0.38) * uSunI * 0.035 * (1.0 - (pr - 1.0) / 0.05) * smoothstep(-0.2, 0.4, dot(limbN, bodyLight(uMarsDir)));
                }
                // far away: a steady red beacon of fixed angular size so the target never sparkles or vanishes
                float minR = 0.0016;
                float angR = 1.0 / uMarsD;
                if (angR < minR) bg += vec3(1.0, 0.42, 0.22) * uSunI * 0.9 * smoothstep(minR, minR * 0.25, sqrt(s2)) * smoothstep(40.0, 90.0, uAltKm);
            }
        }
        // Jupiter: banded clouds, the Great Red Spot, darker poles, and the four Galilean moons nearby
        if (uJupD > 0.0) {
            const float JUP_RKM = 69911.0;
            float ca = dot(rd, uJupDir);
            vec3 cr = cross(rd, uJupDir);
            float s2 = dot(cr, cr);
            float p2 = s2 * uJupD * uJupD;
            vec3 t1 = normalize(cross(uJupDir, vec3(0.0, 0.0, 1.0)));
            vec3 t2 = cross(t1, uJupDir);
            if (ca > 0.0 && p2 < 1.0) {
                float tm = uJupD * ca - sqrt(max(1.0 - p2, 0.0));
                if (tm * JUP_RKM < tHit) {
                    vec3 jn = normalize(rd * tm - uJupDir * uJupD);
                    float lat = dot(jn, t2);
                    float lon = dot(jn, t1);
                    float turb = fbm3(jn * 5.0, 4);
                    float band = sin(lat * 21.0 + turb * 2.6 + sin(lat * 7.0) * 1.3);
                    vec3 cream = vec3(0.87, 0.80, 0.67), tanc = vec3(0.72, 0.52, 0.34), brown = vec3(0.46, 0.31, 0.21);
                    vec3 alb = mix(cream, tanc, smoothstep(-0.2, 0.6, band));
                    alb = mix(alb, brown, smoothstep(0.78, 0.97, band) * 0.6);
                    alb *= 0.86 + 0.28 * fbm3(vec3(lat * 70.0, lon * 4.0 + turb * 3.0, turb), 3);
                    vec2 g = vec2((lon - 0.25) * 3.2, (lat + 0.36) * 8.5);
                    float grs = 1.0 - smoothstep(0.55, 1.0, length(g) + (turb - 0.5) * 0.3);
                    alb = mix(alb, vec3(0.74, 0.34, 0.2), grs * 0.85);
                    alb *= 1.0 - 0.35 * smoothstep(0.72, 0.98, abs(lat));
                    vec3 jl = bodyLight(uJupDir);
                    float nl = max(dot(jn, jl), 0.0);
                    float view = max(dot(jn, -rd), 0.0);
                    vec3 c = alb / PI * uSunI * 0.55 * nl * (0.75 + 0.25 * view);   // 5.2 AU: dimmer sunlight, limb darkening
                    bg = c;
                    tHit = tm * JUP_RKM;
                }
            } else if (ca > 0.0) {
                float minR = 0.0016;
                if (1.0 / uJupD < minR) bg += vec3(1.0, 0.86, 0.62) * uSunI * 0.9 * smoothstep(minR, minR * 0.25, sqrt(s2)) * smoothstep(40.0, 90.0, uAltKm);
            }
            if (uJupD < 3000.0) {
                for (int i = 0; i < 4; i++) {
                    vec4 gd = galData(i);
                    vec3 C = uJupDir * uJupD + t1 * gd.x + t2 * gd.y - uJupDir * gd.z;
                    float b = dot(rd, C);
                    vec3 cc = cross(rd, C);
                    float pp = dot(cc, cc);
                    float rr = gd.w * gd.w;
                    float lc = length(C);
                    // far away: a steady point of fixed size (like Jupiter's moons through binoculars), never a sparkle
                    const float galMinR = 0.0012;
                    if (b > 0.0 && gd.w / lc < galMinR && lc * JUP_RKM < tHit)
                        bg += galColor(i) * uSunI * 0.35 * smoothstep(galMinR, galMinR * 0.25, sqrt(pp) / lc);
                    if (b > 0.0 && pp < rr) {
                        float tg = b - sqrt(rr - pp);
                        if (tg * JUP_RKM < tHit) {
                            vec3 gn = normalize(rd * tg - C);
                            float spots = fbm3(gn * 4.0 + float(i) * 7.0, 3);
                            vec3 alb = galColor(i) * (0.75 + 0.5 * spots);
                            if (i == 1) alb = mix(alb, vec3(0.62, 0.42, 0.3), smoothstep(0.55, 0.62, fbm3(gn * 9.0, 3)) * 0.6);   // Europa's reddish cracks
                            bg = alb / PI * uSunI * 0.6 * max(dot(gn, bodyLight(normalize(C))), 0.0);
                            tHit = tg * JUP_RKM;
                        }
                    }
                }
            }
        }
        // Saturn: an oblate, softly banded globe with the north-polar hexagon, the ring system with shadows both
        // ways, and six moons (orange hazy Titan, Rhea, two-tone Iapetus, Dione, Tethys, bright Enceladus).
        // All maths in equatorial radii with cancellation-free ray tests, so it stays exact 1.2 billion km out.
        if (uSatD > 0.0) {
            const float SAT_RKM = 60268.0;
            const float STRETCH = 1.1086;          // equatorial / polar radius
            vec3 n = uRingN;
            vec3 C = uSatDir * uSatD;
            vec3 sl = bodyLight(uSatDir);          // sunlight direction in the Saturn system
            float ca = dot(rd, uSatDir);
            vec3 rds = rd + n * (dot(rd, n) * (STRETCH - 1.0));
            vec3 Cs = C + n * (dot(C, n) * (STRETCH - 1.0));
            vec3 crs = cross(rds, Cs);
            float a2 = dot(rds, rds);
            float disc = a2 - dot(crs, crs);
            float tP = 1e30;
            vec3 col = bg;
            float angR = 1.0 / uSatD;
            const float satMinR = 0.0016;
            if (ca > 0.0 && disc > 0.0) {
                tP = (dot(rds, Cs) - sqrt(disc)) / a2;
                if (tP * SAT_RKM < tHit) {
                    vec3 H = rd * tP - C;                                  // hit point relative to the centre
                    vec3 hs = H + n * (dot(H, n) * (STRETCH - 1.0));
                    vec3 gn = normalize(hs + n * (dot(hs, n) * (STRETCH - 1.0)));   // spheroid normal
                    float lat = dot(normalize(hs), n);
                    vec3 t1 = normalize(cross(n, uSatDir)); vec3 t2 = cross(n, t1);
                    float lon = atan(dot(hs, t2), dot(hs, t1));
                    float turb = fbm3(normalize(hs) * 4.0, 4);
                    float band = sin(lat * 26.0 + turb * 1.6 + sin(lat * 9.0) * 0.8);
                    vec3 alb = mix(vec3(0.87, 0.79, 0.59), vec3(0.74, 0.62, 0.42), smoothstep(-0.3, 0.7, band));
                    alb = mix(alb, vec3(0.58, 0.48, 0.34), smoothstep(0.85, 0.99, band) * 0.4);
                    alb *= 0.9 + 0.2 * fbm3(vec3(lat * 60.0, lon * 3.0, turb), 3);
                    // the north-polar hexagon (a blue-grey six-sided jet stream)
                    float pr = sqrt(max(0.0, 1.0 - lat * lat));
                    float hexR = 0.22 / cos(mod(lon + 3.0 * PI, PI / 3.0) - PI / 6.0);
                    alb = mix(alb, vec3(0.5, 0.58, 0.68), (1.0 - smoothstep(hexR * 0.9, hexR, pr)) * step(0.0, lat) * 0.8);
                    float nl = max(dot(gn, sl), 0.0);
                    float view = max(dot(gn, -rd), 0.0);
                    vec3 c = alb / PI * uSunI * 0.5 * nl * (0.72 + 0.28 * view);
                    // the rings cast their shadow on the globe
                    float sn = dot(sl, n);
                    if (abs(sn) > 1e-3) {
                        float ts = -dot(H, n) / sn;
                        if (ts > 0.0) c *= 1.0 - 0.85 * ringOpacity(length(H + sl * ts), 0.0);
                    }
                    col = c;
                    tHit = tP * SAT_RKM;
                }
            } else if (ca > 0.0 && angR < satMinR) {
                // far away: a steady pale-gold point of fixed size
                float s2 = dot(cross(rd, uSatDir), cross(rd, uSatDir));
                col += vec3(1.0, 0.88, 0.6) * uSunI * 0.9 * smoothstep(satMinR, satMinR * 0.25, sqrt(s2)) * smoothstep(40.0, 90.0, uAltKm);
            }
            // rings (translucent, in front of whatever is behind them)
            float dn = dot(rd, n);
            if (abs(dn) > 1e-6 && angR * 2.4 > satMinR * 0.5) {
                float tR = dot(C, n) / dn;
                if (tR > 0.0 && tR * SAT_RKM < tHit) {
                    vec3 Q = rd * tR - C;
                    float rho = length(Q);
                    float op = ringOpacity(rho, fwidth(rho));
                    if (op > 0.0) {
                        // in Saturn's shadow?
                        float qs = dot(Q, sl);
                        float shadow = (qs < 0.0 && length(Q - sl * qs) < 0.95) ? 0.12 : 1.0;
                        // lit face or the unlit face (seen through)
                        float sameSide = sign(dot(sl, n)) * sign(-dn);
                        float lit = sameSide > 0.0 ? 1.0 : 0.35;
                        vec3 rc = ringColor(rho) / PI * uSunI * 0.55 * lit * shadow * (0.35 + 0.65 * abs(dot(sl, n)) + 0.25);
                        col = mix(col, rc, op);
                    }
                }
            }
            bg = col;
            // moons
            if (uSatD < 3000.0) {
                vec3 u1 = normalize(cross(n, uSatDir)); vec3 u2 = cross(n, u1);
                for (int i = 0; i < 6; i++) {
                    vec4 md = satMoon(i);
                    vec3 Cm = C + (u1 * cos(md.y) + u2 * sin(md.y)) * md.x + n * md.w;
                    float b = dot(rd, Cm);
                    vec3 cc = cross(rd, Cm);
                    float pp = dot(cc, cc);
                    float rr = md.z * md.z;
                    float lc = length(Cm);
                    const float smMinR = 0.0012;
                    if (b > 0.0 && md.z / lc < smMinR && lc * SAT_RKM < tHit)
                        bg += satMoonColor(i) * uSunI * 0.3 * smoothstep(smMinR, smMinR * 0.25, sqrt(pp) / lc);
                    if (b > 0.0 && pp < rr) {
                        float tg = b - sqrt(rr - pp);
                        if (tg * SAT_RKM < tHit) {
                            vec3 mn = normalize(rd * tg - Cm);
                            vec3 alb = satMoonColor(i) * (0.8 + 0.4 * fbm3(mn * 5.0 + float(i) * 3.0, 3));
                            if (i == 2) alb *= mix(0.18, 1.0, smoothstep(-0.1, 0.1, dot(mn, u1)));     // Iapetus: dark leading half
                            if (i == 5) alb = mix(alb, vec3(0.55, 0.8, 1.0), smoothstep(0.62, 0.66, fbm3(mn * 8.0, 3)) * smoothstep(0.3, -0.6, dot(mn, n)));   // Enceladus' blue "tiger stripes"
                            float hz = i == 0 ? pow(1.0 - max(dot(mn, -rd), 0.0), 2.0) : 0.0;          // Titan's orange haze limb
                            bg = alb / PI * uSunI * 0.5 * max(dot(mn, bodyLight(normalize(Cm))), 0.0) + vec3(0.9, 0.5, 0.15) * uSunI * 0.03 * hz;
                            tHit = tg * SAT_RKM;
                        }
                    } else if (i == 0 && b > 0.0 && pp < rr * 1.25) {
                        // Titan's thick atmosphere glows just outside its limb
                        float ex = (sqrt(pp) - md.z) / (md.z * 0.12);
                        bg += vec3(0.95, 0.55, 0.2) * uSunI * 0.025 * (1.0 - clamp(ex, 0.0, 1.0));
                    }
                }
            }
        }
    }
    // cloud deck shell (seen from below as overcast patches, from above as a sea of clouds)
    float Rd = uRkm + uDeckKm;
    float cD = uC0 - uDeckKm * (2.0 * uRkm + uDeckKm);
    float dD = b * b - cD;
    float near = abs(uAltKm - uDeckKm);
    if (dD >= 0.0 && near > 0.25) {
        float tD = -1.0;
        if (cD < 0.0) tD = -b + sqrt(dD);
        else if (b < 0.0) tD = cD / (-b + sqrt(dD));
        if (tD > 0.0 && tD < tHit) {
            vec3 dp = ro + rd * tD;
            float cov = cloudCover(normalize(dp), tD) * smoothstep(0.25, 1.2, near) * exp(-tD / 400.0);
            float nl = max(dot(normalize(dp), uSun), 0.0);
            vec3 cc = cloudColor(cov) / PI * uSunI * (0.35 + 0.65 * nl);
            if (cD < 0.0) cc *= 0.75;                    // undersides are a little darker
            bg = mix(bg, cc, cov);
        }
    }
    o = vec4((ins + bg * T) * uOutScale, 1.0);
}
"""

    // ------------------------------------------------------------------ stars (stable point sprites — no shimmer)
    val STAR_VS = V + """
layout(location = 0) in vec3 aDir;
layout(location = 1) in vec2 aMag;   // brightness, colour temperature (0 = red, 1 = blue)
uniform mat4 uVP; uniform float uRkm; uniform float uC0; uniform float uAltKm; uniform float uPx;
uniform vec3 uMoonPos; uniform float uMoonR;
uniform vec3 uMarsDir; uniform float uMarsD;
uniform vec3 uJupDir; uniform float uJupD;
uniform vec3 uSatDir; uniform float uSatD;
out vec3 vC;
void main() {
    vec3 d = normalize(aDir);
    float b = (uRkm + max(uAltKm, 0.002)) * d.y;
    bool hidden = b < 0.0 && b * b - uC0 >= 0.0;
    float mb = -dot(uMoonPos, d);
    if (uMoonR > 0.0 && mb < 0.0 && mb * mb - dot(uMoonPos, uMoonPos) + uMoonR * uMoonR > 0.0) hidden = true;
    vec3 mc = cross(d, uMarsDir);
    if (uMarsD > 0.0 && dot(d, uMarsDir) > 0.0 && dot(mc, mc) * uMarsD * uMarsD < 1.1) hidden = true;
    vec3 jc = cross(d, uJupDir);
    if (uJupD > 0.0 && dot(d, uJupDir) > 0.0 && dot(jc, jc) * uJupD * uJupD < 1.1) hidden = true;
    vec3 sc = cross(d, uSatDir);
    if (uSatD > 0.0 && dot(d, uSatDir) > 0.0 && dot(sc, sc) * uSatD * uSatD < 1.1) hidden = true;
    gl_Position = uVP * vec4(d, 1.0);
    gl_Position.z = gl_Position.w * 0.9999;
    gl_PointSize = hidden ? 0.0 : uPx * (1.2 + 1.6 * aMag.x);
    vC = mix(vec3(1.0, 0.75, 0.55), vec3(0.7, 0.82, 1.0), aMag.y) * aMag.x;
}
"""
    val STAR_FS = V + FP + """
in vec3 vC; out vec4 o; uniform float uVis; uniform float uOutScale;
void main() {
    vec2 q = gl_PointCoord * 2.0 - 1.0;
    float a = exp(-dot(q, q) * 3.0);
    o = vec4(vC * a * uVis * 6.0 * uOutScale, 1.0);
}
"""

    // ------------------------------------------------------------------ exhaust plume (volumetric-looking shells)
    val PLUME_VS = V + """
layout(location = 0) in vec3 aPos;     // unit plume: y from 0 (nozzle) to -1 (tip), radius in xz
layout(location = 1) in vec3 aNrm;
uniform mat4 uVP; uniform mat4 uModel; uniform float uLen; uniform float uRad; uniform float uExpand;
out float vT; out vec3 vN; out vec3 vW;
void main() {
    float t = -aPos.y;
    float widen = 1.0 + uExpand * t * (1.2 + t);
    vec3 p = vec3(aPos.x * uRad * widen, aPos.y * uLen, aPos.z * uRad * widen);
    vec4 w = uModel * vec4(p, 1.0);
    vW = w.xyz; vT = t;
    vN = normalize(mat3(uModel) * vec3(aNrm.x, aNrm.y * uRad / uLen, aNrm.z));
    gl_Position = uVP * w;
}
"""
    val PLUME_FS = V + FP + """
in float vT; in vec3 vN; in vec3 vW; out vec4 o;
uniform vec3 uCore; uniform vec3 uOuter; uniform float uIntensity; uniform float uDiamonds; uniform float uTime; uniform float uOutScale; uniform float uSeed;
uniform vec3 uDiaCol;   // mach-diamond / plasma-striation colour (per exhaust type)
void main() {
    vec3 V = normalize(-vW);
    float facing = abs(dot(normalize(vN), V));
    float body = pow(facing, 1.6);                       // soft edges -> volumetric look
    float t = vT;
    float along = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.55, 1.0, t));
    float flick = 0.85 + 0.15 * sin(t * 37.0 - uTime * 61.0 + uSeed) * sin(t * 13.0 - uTime * 23.0);
    vec3 col = mix(uCore, uOuter, smoothstep(0.0, 0.7, t));
    float dia = uDiamonds * pow(0.5 + 0.5 * cos(t * 6.2831 * 5.0), 8.0) * (1.0 - smoothstep(0.1, 0.6, t));
    vec3 c = col * (body * along * flick) + uDiaCol * dia * body;
    o = vec4(c * uIntensity * uOutScale, 1.0);
}
"""

    // ------------------------------------------------------------------ particles / clouds (instanced billboards)
    val BB_VS = V + """
layout(location = 0) in vec2 aCorner;
layout(location = 1) in vec4 aPosSize;   // camera-relative position, size
layout(location = 2) in vec4 aColor;
layout(location = 3) in vec4 aParams;    // kind, rotation, life (0..1), seed
uniform mat4 uVP; uniform vec3 uRight; uniform vec3 uUp; uniform vec3 uFwd;
out vec2 vUV; out vec4 vColor; out vec4 vParams; out float vFade;
void main() {
    float c = cos(aParams.y), s = sin(aParams.y);
    vec2 q = vec2(c * aCorner.x - s * aCorner.y, s * aCorner.x + c * aCorner.y);
    vec3 p = aPosSize.xyz + (uRight * q.x + uUp * q.y) * aPosSize.w;
    vUV = aCorner; vColor = aColor; vParams = aParams;
    float depth = dot(aPosSize.xyz, uFwd);
    // fade particles that get close to the camera instead of letting big quads pop through the near plane
    vFade = smoothstep(0.3, 1.0 + aPosSize.w * 0.9, depth);
    gl_Position = uVP * vec4(p, 1.0);
}
"""
    val BB_FS = V + FP + NOISE + """
in vec2 vUV; in vec4 vColor; in vec4 vParams; in float vFade; out vec4 o;
uniform vec3 uSunV; uniform vec3 uSunCol; uniform vec3 uAmb; uniform float uOutScale;
void main() {
    float r2 = dot(vUV, vUV);
    if (r2 > 1.0) discard;
    int kind = int(vParams.x + 0.5);
    float seed = vParams.w * 37.0;
    float fadeIn = smoothstep(0.0, 0.06, vParams.z);
    if (kind == 1) {          // flame (additive)
        float a = pow(1.0 - r2, 1.8);
        o = vec4(vColor.rgb * a * vColor.a * fadeIn * vFade * uOutScale, 1.0);
        return;
    }
    if (kind == 3) {          // spark (additive)
        float a = exp(-r2 * 7.0);
        o = vec4(vColor.rgb * a * vColor.a * vFade * uOutScale * 3.0, 1.0);
        return;
    }
    // smoke (0) and clouds (2): noisy puffs lit from the sun with a pseudo-normal
    float n = fbm2(vUV * (kind == 2 ? 1.6 : 2.2) + seed, kind == 2 ? 4 : 3);
    float shape = smoothstep(1.0, 0.25, r2 + (0.5 - n) * 0.9);
    vec3 nrm = normalize(vec3(vUV, sqrt(max(0.0, 1.0 - r2)) + 0.3));
    float lit = clamp(dot(nrm, uSunV) * 0.5 + 0.55, 0.0, 1.0);
    float silver = kind == 2 ? pow(1.0 - r2, 0.5) * 0.0 + pow(max(0.0, 1.0 - shape), 2.0) * 0.4 * max(uSunV.z * -1.0, 0.0) : 0.0;
    vec3 light = uSunCol * (lit + silver) + uAmb * (kind == 2 ? 1.1 : 1.0);
    vec3 base = vColor.rgb * (kind == 2 ? mix(0.82, 1.0, n) : mix(0.7, 1.05, n));
    float a = shape * vColor.a * fadeIn * vFade;
    o = vec4(base * light * uOutScale * a, a);   // premultiplied alpha
}
"""

    // ------------------------------------------------------------------ post: bloom + composite
    val BLOOM_DOWN_FS = V + FP + """
in vec2 vUV; out vec4 o;
uniform sampler2D uTex; uniform vec2 uTexel; uniform float uPrefilter; uniform float uThreshold; uniform float uInScale;
vec3 pre(vec3 c) {
    c *= uInScale;
    float br = max(c.r, max(c.g, c.b));
    float knee = uThreshold * 0.5;
    float soft = clamp(br - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float w = max(soft, br - uThreshold) / max(br, 1e-4);
    return c * w;
}
float karis(vec3 c) { return 1.0 / (1.0 + max(c.r, max(c.g, c.b))); }
void main() {
    vec3 a = texture(uTex, vUV + uTexel * vec2(-1.0, -1.0)).rgb;
    vec3 b = texture(uTex, vUV + uTexel * vec2(1.0, -1.0)).rgb;
    vec3 c = texture(uTex, vUV + uTexel * vec2(-1.0, 1.0)).rgb;
    vec3 d = texture(uTex, vUV + uTexel * vec2(1.0, 1.0)).rgb;
    vec3 e = texture(uTex, vUV).rgb;
    if (uPrefilter > 0.5) {
        a = pre(a); b = pre(b); c = pre(c); d = pre(d); e = pre(e);
        // Karis average: stops single bright pixels from flickering in the bloom
        float wa = karis(a), wb = karis(b), wc = karis(c), wd = karis(d), we = karis(e) * 4.0;
        o = vec4((a * wa + b * wb + c * wc + d * wd + e * we) / (wa + wb + wc + wd + we), 1.0);
    } else {
        o = vec4((a + b + c + d + e * 4.0) / 8.0, 1.0);
    }
}
"""
    val BLOOM_UP_FS = V + FP + """
in vec2 vUV; out vec4 o;
uniform sampler2D uTex; uniform vec2 uTexel; uniform float uRadius;
void main() {
    vec2 t = uTexel * uRadius;
    vec3 s = texture(uTex, vUV + vec2(-t.x * 2.0, 0.0)).rgb + texture(uTex, vUV + vec2(t.x * 2.0, 0.0)).rgb
           + texture(uTex, vUV + vec2(0.0, -t.y * 2.0)).rgb + texture(uTex, vUV + vec2(0.0, t.y * 2.0)).rgb;
    s += (texture(uTex, vUV + vec2(-t.x, -t.y)).rgb + texture(uTex, vUV + vec2(t.x, -t.y)).rgb
        + texture(uTex, vUV + vec2(-t.x, t.y)).rgb + texture(uTex, vUV + vec2(t.x, t.y)).rgb) * 2.0;
    o = vec4(s / 12.0, 1.0);
}
"""
    val COMPOSITE_FS = V + FP + NOISE + """
in vec2 vUV; out vec4 o;
uniform sampler2D uScene; uniform sampler2D uBloom;
uniform float uBloomOn; uniform float uBloomStrength; uniform float uInScale; uniform float uExposure;
uniform vec4 uShimmerSeg; uniform float uShimmer; uniform float uTime; uniform float uAspect;
uniform float uWhiteout; uniform vec3 uWhiteCol; uniform float uFlash; uniform float uVignette;
// ACES fitted (Stephen Hill): sRGB -> ACEScg-ish -> RRT+ODT fit -> sRGB
vec3 aces(vec3 c) {
    const mat3 I = mat3(0.59719, 0.07600, 0.02840, 0.35458, 0.90834, 0.13383, 0.04823, 0.01566, 0.83777);
    const mat3 O = mat3(1.60475, -0.10208, -0.00327, -0.53108, 1.10813, -0.07276, -0.07367, -0.00605, 1.07602);
    c = I * c;
    vec3 a = c * (c + 0.0245786) - 0.000090537;
    vec3 b = c * (0.983729 * c + 0.4329510) + 0.238081;
    return clamp(O * (a / b), 0.0, 1.0);
}
void main() {
    vec2 uv = vUV;
    if (uShimmer > 0.0) {
        // heat haze: distort along the exhaust segment in screen space
        vec2 a = uShimmerSeg.xy, b = uShimmerSeg.zw;
        vec2 ab = b - a;
        float t = clamp(dot(uv - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
        vec2 d = (uv - (a + ab * t)) * vec2(uAspect, 1.0);
        float w = exp(-dot(d, d) / (0.0025 + 0.012 * t)) * (1.0 - t * 0.6);
        vec2 n = vec2(vnoise2(uv * 70.0 + vec2(0.0, uTime * 9.0)), vnoise2(uv * 70.0 + vec2(5.2, uTime * 9.0 + 3.1))) - 0.5;
        uv += n * 0.012 * uShimmer * w;
    }
    vec3 c = texture(uScene, uv).rgb * uInScale;
    if (uBloomOn > 0.5) c += texture(uBloom, vUV).rgb * uBloomStrength;
    c = mix(c, uWhiteCol, uWhiteout);
    c += vec3(1.0, 0.85, 0.6) * uFlash;
    c *= uExposure;
    vec3 m = aces(c);
    m = pow(m, vec3(1.0 / 2.2));
    vec2 q = vUV - 0.5;
    m *= 1.0 - uVignette * dot(q, q) * 1.6;
    m += (hash12(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;   // dither: no banding in the sky gradient
    o = vec4(m, 1.0);
}
"""
}
