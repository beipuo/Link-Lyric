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

// 描边宽度（像素）。0 表示不描边。
uniform float StrokeWidth;
// 描边颜色，已含 alpha。
uniform vec4 StrokeColor;

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

    // 轮廓外的像素数。距离场里每条等值线都是轮廓的平移，
    // 所以「轮廓外 n 像素」就是 sd 比 EDGE 小 n 个 aa 的那一圈，
    // 与字号、缩放都无关。
    float pixelsOut = (EDGE - sd) / aa * Sharpness;

    // 描边：轮廓外 [0, StrokeWidth] 像素这一圈。
    //
    // 与发光的区别在衰减：发光是平方衰减的软边，描边要在整圈上等宽、
    // 等浓，边缘只留 aa 那一点点过渡。所以这里用 step 式的硬边界，
    // 再用像素数做一像素宽的抗锯齿——否则描边会糊成一圈光晕。
    float stroke = 0.0;
    if (StrokeWidth > 0.0) {
        // 用 1 像素的过渡代替 step，边缘才不会出现锯齿。
        stroke = clamp(StrokeWidth + 0.5 - pixelsOut, 0.0, 1.0);
        // 描边只画在字形外面：笔画内部交给正文，否则描边色会盖住字。
        stroke *= (1.0 - coverage);
    }

    float glow = 0.0;
    if (GlowRadius > 0.0 && GlowAlpha > 0.0) {
        // 半径受留白约束。GlowLimit 由烘焙参数算出并作为 uniform 传入，
        // 因此换字体、改留白、改展开倍数都会自动跟着变，不需要在这里
        // 写死与字号挂钩的经验值。
        float radius = min(GlowRadius, GlowLimit);

        // 光晕从描边**外缘**起算。
        //
        // 直接取 pixelsOut / radius 会让发光的最内圈正好压在描边上：
        // 描边本来是一圈实色，被发光以「alpha 加权」混入白色后变淡，
        // 看起来像描边没画满。减掉描边宽度后，光晕整段落在描边之外，
        // 两者各占各的距离区间，互不冲淡。
        float start = StrokeWidth > 0.0 ? StrokeWidth : 0.0;
        float span = max(radius - start, 1e-4);

        // 平方衰减。线性衰减在最外圈留下一道可见的硬边，
        // 观感像描边；平方后两端都收敛到零，才是散开。
        float falloff = clamp(1.0 - (pixelsOut - start) / span, 0.0, 1.0);
        glow = falloff * falloff * GlowAlpha * (1.0 - coverage) * (1.0 - stroke);
    }

    // 三层的权重互不重叠：coverage 在笔画内，stroke 与 glow 在笔画外，
    // 因此 total 就是「这个像素被墨迹占了多少」。
    float total = coverage + stroke + glow;
    if (total < 0.01) {
        discard;
    }

    // 非预乘 alpha 合成：先按各层权重把 rgb 加权平均，
    // 再由 alpha 交给混合阶段去和背景合成。
    //
    // 描边的 alpha 与正文的 alpha 可能不同（描边常有独立的透明度），
    // 所以不能只取一个 alpha 乘 total——那样描边会被正文的透明度带偏。
    // 这里把「各层 alpha × 各层权重」求和作为最终 alpha，
    // rgb 则按各自的 alpha 权重归一化。
    float wBody = vertexColor.a * coverage;
    float wStroke = StrokeColor.a * stroke;
    float wGlow = vertexColor.a * glow;
    float alpha = wBody + wStroke + wGlow;

    vec3 rgb = (vertexColor.rgb * wBody
                + StrokeColor.rgb * wStroke
                + vec3(1.0) * wGlow) / max(alpha, 1e-4);
    fragColor = vec4(rgb, alpha) * ColorModulator;
}