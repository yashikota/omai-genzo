#version 300 es
layout(location = 0) in vec2 a_position; // Vertex position [-1.0, 1.0]
layout(location = 1) in vec2 a_texCoord; // Texture coordinates [0.0, 1.0]

out vec2 v_texCoord;

void main() {
    gl_Position = vec4(a_position, 0.0, 1.0);
    v_texCoord = a_texCoord;
}
