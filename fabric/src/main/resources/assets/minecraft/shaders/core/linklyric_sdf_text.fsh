#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
// 抗锯齿过渡宽度倍数：1.0 约等于一像素宽。
uniform float Sharpness;
// 笔画加粗（像素）。小字号发灰时补偿，只加厚不影响字距。
uniform float Weight;
// 阴影柔化倍数：1.0 等于正文的锐利度，越大边缘越糊。
uniform float Softness;
// 外发光半径，以屏幕像素计。0 表示不发光。
uniform float GlowRadius;
// 外发光强度，0..1。
uniform float GlowAlpha;
// 图集留白能支撑的最大距离（场值单位，1 等于 128/spread）。
//
// 光晕按距离衰减，但图集每边的留白是有限的：越过留白边缘就采到相邻
// 字形或 CLAMP 的边缘像素，衰减被硬生生切断，看起来像给字套了一圈
// 描边而不是散开。所以半径必须按留白封顶，而不是按字号随意取。
uniform float GlowLimit;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// 图集把距离编码为 onedge = 128，轮廓就落在这里。
const float EDGE = 128.0 / 255.0;

void main() {
    float sd = texture(Sampler0, texCoord0).r;

    // 屏幕空间的场变化率，保证无论怎样缩放或变换，边缘都是一像素宽。
    //
    // 必须用真实的梯度模长，不能用 fwidth。
    // fwidth = |dFdx| + |dFdy| 只对轴对齐的边缘正确；
    // 在 45 度边上它高估 sqrt(2) 倍，会把过渡糊成 1.4 像素。
    // 结果是直笔锋利、每个曲线和斜笔发虚，整体读起来就是全字模糊。
    vec2 gradient = vec2(dFdx(sd), dFdy(sd));
    float aa = max(length(gradient), 1e-5) * max(Softness, 0.01);

    // Weight 直接平移阈值即可加粗：距离场里轮廓是等值线，
    // 把阈值往小的方向推，等值线就向外扩，笔画随之变粗。
    // 用加法而不是乘法，是因为它在整条等值线上等宽，
    // 乘法会让细笔画扩得比粗笔画少，字重变化不均匀。
    float coverage = clamp((sd - EDGE) / aa * Sharpness + 0.5 + Weight, 0.0, 1.0);

    float glow = 0.0;
    if (GlowRadius > 0.0 && GlowAlpha > 0.0) {
        // 轮廓外的像素数。距离场里每条等值线都是轮廓的平移，
        // 所以「轮廓外 n 像素」就是 sd 比 EDGE 小 n 个 aa 的那一圈，
        // 与字号、缩放都无关。
        float pixelsOut = (EDGE - sd) / aa * Sharpness;

        // 半径受留白约束。GlowLimit 由烘焙参数算出并作为 uniform 传入，
        // 因此换字体、改留白、改展开倍数都会自动跟着变，不需要在这里
        // 写死与字号挂钩的经验值。
        float radius = min(GlowRadius, GlowLimit);

        // 平方衰减。线性衰减在最外圈留下一道可见的硬边，
        // 观感像描边；平方后两端都收敛到零，才是散开。
        float falloff = clamp(1.0 - pixelsOut / max(radius, 1e-4), 0.0, 1.0);
        glow = falloff * falloff * GlowAlpha * (1.0 - coverage);
    }

    float alpha = coverage + glow;
    if (alpha < 0.01) {
        discard;
    }

    // 字形内部显示文字色，光晕部分显示白色，按各自覆盖度加权。
    // 这是非预乘 alpha 的正确合成：rgb 除以总 alpha 还原出颜色，
    // 再由 fragColor 的 alpha 交给混合阶段。
    vec3 rgb = (vertexColor.rgb * coverage + vec3(1.0) * glow) / alpha;
    fragColor = vec4(rgb, vertexColor.a * alpha) * ColorModulator;
}
