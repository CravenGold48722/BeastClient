#version 330

#moj_import <minecraft:dynamictransforms.glsl>

// Sampler0: the glyph sheet, Sampler1: the framebuffer the text is drawn into
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

// The text takes the opposite brightness of whatever is behind each pixel,
// in gray only: white on black, black on white, gray on gray.
void main() {
    float mask = texture(Sampler0, texCoord0).a;
    if (mask == 0.0) {
        discard;
    }

    vec3 behind = texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).rgb;
    float brightness = dot(behind, vec3(0.299, 0.587, 0.114));
    fragColor = vec4(vec3(1.0 - brightness), mask * vertexColor.a)
        * ColorModulator;
}
