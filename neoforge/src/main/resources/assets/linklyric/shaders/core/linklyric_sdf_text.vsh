#version 150

#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    // GUI 文字不吃光照：HUD 上没有世界光照图可采，UV2 保留只为顶点格式对齐。
    vertexColor = Color;
}
