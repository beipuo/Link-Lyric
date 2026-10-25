#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
// 抗锯齿过渡宽度倍数：1.0 约等于一像素宽。
uniform float Sharpness;
// 笔画加粗（像素）。小字号发灰时补偿，只加厚不影响字距。
uniform float Weight;

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
    float aa = max(length(gradient), 1e-5);
    float coverage = clamp((sd - EDGE) / aa * Sharpness + 0.5 + Weight, 0.0, 1.0);

    vec4 color = vec4(vertexColor.rgb, vertexColor.a * coverage) * ColorModulator;
    if (color.a < 0.01) {
        discard;
    }
    fragColor = color;
}
