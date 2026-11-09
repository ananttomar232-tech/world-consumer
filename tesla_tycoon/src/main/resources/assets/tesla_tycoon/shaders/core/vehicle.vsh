#version 150

// Tesla Tycoon vehicle vertex shader. Vertex format = vanilla ENTITY format; the overlay/light attributes carry material data:
//   UV1 = (metallic, roughness) 0..255      UV2 = (emissive 0..255, untextured flag)

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 NormalMat;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 viewNormal;
out vec3 viewDir;
out vec3 matParams;
out float untextured;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    vertexDistance = length(viewPos.xyz);
    vertexColor = Color;
    texCoord0 = UV0;
    viewNormal = NormalMat * Normal;
    viewDir = ProjMat[3][3] > 0.5 ? vec3(0.0, 0.0, 1.0) : normalize(-viewPos.xyz);
    matParams = vec3(float(UV1.x), float(UV1.y), float(UV2.x)) / 255.0;
    untextured = float(UV2.y);
}
