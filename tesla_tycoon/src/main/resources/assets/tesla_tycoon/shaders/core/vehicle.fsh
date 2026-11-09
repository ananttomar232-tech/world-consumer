#version 150

// Tesla Tycoon vehicle fragment shader: vertex colour (x optional texture), two vanilla directional lights, the world lightmap,
// a cheap sky/ground reflection so dark glossy paint keeps its shape, emissive lights, translucent glass and fog.

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform vec4 ColorModulator;
uniform vec4 Params;            // x alpha multiplier, y model has a texture, z additive (flame) pass
uniform vec2 LightCoords;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewDir;
in vec3 matParams;
in float untextured;

out vec4 fragColor;

void main() {
    vec4 albedo = vertexColor;
    if (Params.y > 0.5 && untextured < 0.5) {
        albedo *= texture(Sampler0, texCoord0);
    }
    float metal = matParams.x;
    float rough = matParams.y;
    float emis = matParams.z;

    vec3 n = normalize(viewNormal);
    if (!gl_FrontFacing) {
        n = -n;
    }
    vec3 l0 = normalize(Light0_Direction);
    vec3 l1 = normalize(Light1_Direction);
    float diffuse = min(1.0, (max(0.0, dot(n, l0)) + max(0.0, dot(n, l1))) * 0.6 + 0.4);

    vec3 base = max(albedo.rgb, vec3(0.04));
    vec3 v = normalize(viewDir);
    vec3 up = normalize(l0 + l1);                       // the two vanilla lights are mirrored about "up"
    vec3 r = reflect(-v, n);
    float skyness = smoothstep(-0.1, 0.65, dot(r, up));
    vec3 env = mix(vec3(0.04, 0.045, 0.06), vec3(0.62, 0.78, 1.0), skyness);
    float fres = pow(1.0 - max(dot(n, v), 0.0), 3.0);
    float refl = mix(0.06, 0.55, metal) * (1.0 - 0.55 * rough);
    vec3 lm = texelFetch(Sampler2, ivec2(clamp(LightCoords, 0.0, 15.0)), 0).rgb;

    vec3 lit = base * diffuse * lm;
    float shin = mix(120.0, 10.0, rough);
    float spec = pow(max(dot(n, normalize(l0 + v)), 0.0), shin) + pow(max(dot(n, normalize(l1 + v)), 0.0), shin);
    lit += vec3(spec) * (0.12 + 0.3 * metal) * (1.0 - rough * 0.6) * lm;
    lit += env * refl * (0.35 + 1.4 * fres) * lm;

    vec3 color = mix(lit, albedo.rgb * 1.15, emis);
    float alpha = albedo.a * Params.x;
    vec4 outColor = vec4(color, alpha) * ColorModulator;

    if (vertexDistance > FogStart) {
        float fog = vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0;
        if (Params.z > 0.5) {
            outColor.a *= 1.0 - fog;                    // additive effects fade out instead of turning fog coloured
        } else {
            outColor = vec4(mix(outColor.rgb, FogColor.rgb, fog * FogColor.a), outColor.a);
        }
    }
    fragColor = outColor;
}
