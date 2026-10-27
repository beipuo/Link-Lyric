#version 150

// 每批变化的排版参数。1.21.6 起所有自定义 uniform 都必须待在 std140
// uniform 块里：标量 uniform 不能再单独声明，RenderPipeline 也只认识的
// UNIFORM_BUFFER，绑定走 RenderPass.setUniform(name, GpuBufferSlice)。
//
// 字段顺序必须与 SdfPassDrawer 里的 Std140Builder 打包顺序逐字对应，
// 否则参数会整体错位一格——而且不会报错，只会画错。
layout(std140) uniform SdfParams {
    // x 轴距离场的放大比例：值越大边缘越硬。
    float Sharpness;
    // 字重，单位是图集坐标的像素。
    float Weight;
    // 边缘柔化半径。
    float Softness;
    // 外发光半径；0 表示不发光。
    float GlowRadius;
    // 外发光强度。
    float GlowAlpha;
    // 超过这个距离就不再计入发光，用于贴合逐字形的留白。
    float GlowLimit;
    // 描边宽度；0 表示不描边。
    float StrokeWidth;
    // 描边颜色，已预乘 alpha。
    vec4 StrokeColor;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    // 图集是单通道 RED8，采样得到的是「到字形边缘的有符号距离」。
    float dist = texture(Sampler0, texCoord0).r;

    // 距离场以 0.5 为等值线。Sharpness 放大后按 Softness 做一次平滑，
    // 得到抗锯齿的覆盖率。
    float d = (dist - 0.5) * Sharpness - Weight;
    float coverage = smoothstep(-Softness, Softness, d);

    vec4 color = vertexColor * coverage;

    // 描边：紧贴等值线外侧的一圈。
    if (StrokeWidth > 0.0) {
        float outer = smoothstep(-Softness, Softness, d + StrokeWidth * Sharpness);
        float ring = clamp(outer - coverage, 0.0, 1.0);
        color = mix(color, StrokeColor, ring * StrokeColor.a);
    }

    // 外发光：等值线外侧按半径衰减，超过 GlowLimit 截断。
    if (GlowRadius > 0.0 && GlowAlpha > 0.0) {
        float glow = 1.0 - smoothstep(0.0, GlowRadius * Sharpness, -d);
        glow *= step(d, GlowLimit);
        color.rgb += vertexColor.rgb * glow * GlowAlpha;
        color.a = max(color.a, glow * GlowAlpha);
    }

    if (color.a < 0.01) {
        discard;
    }
    fragColor = color;
}
