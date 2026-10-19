package app.floatdeck.gl

/** GLSL 着色器源码：肖像卡片（portrait）和背景各一组顶点/片段着色器。 */
object Shaders {
    /** 肖像卡片顶点着色器：处理旋转、缩放、偏移和视差效果。 */
    val portraitVertex =
        """
        #version 300 es
        precision highp float;

        uniform mat4 uMVP;
        uniform vec2 uOffset;
        uniform float uRotation;
        uniform vec2 uScale;
        uniform vec2 uParallax;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aUV;

        out vec2 vUV;
        out vec2 vLocalPos;

        void main() {
            // 应用旋转
            float c = cos(uRotation);
            float s = sin(uRotation);
            vec2 rotated = vec2(
                aPosition.x * c - aPosition.y * s,
                aPosition.x * s + aPosition.y * c
            );

            // 应用缩放
            vec2 scaled = rotated * uScale;

            // 应用偏移 + 视差
            vec2 pos = scaled + uOffset + uParallax;

            gl_Position = uMVP * vec4(pos, 0.0, 1.0);
            vUV = aUV;
            vLocalPos = aPosition;
        }
        """.trimIndent()

    /** Portrait card fragment shader: rounded-rect clipping + shadow + texture sampling (with texture alpha) + card effects, outputting premultiplied-alpha color. */
    val portraitFragment =
        """
        #version 300 es
        precision highp float;

        uniform sampler2D uTexture;
        uniform float uAlpha;
        uniform vec4 uShadowColor;
        uniform vec2 uShadowOffset;
        uniform float uRadius;
        // 立绘特效：0=无, 1=碎碎冰, 2=炫彩
        uniform int uEffect;
        uniform float uTime;
        // 炫彩视角偏移（来自陀螺仪）
        uniform vec2 uViewAngle;

        in vec2 vUV;
        in vec2 vLocalPos;

        out vec4 fragColor;

        // 圆角矩形 SDF（有符号距离场）
        float roundedBoxSDF(vec2 centerPos, vec2 size, float radius) {
            vec2 q = abs(centerPos) - size + radius;
            return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
        }

        // ---- 碎碎冰效果 ----
        // 基于网格的冰晶纹理，边缘发光，淡蓝色覆盖
        vec3 applyIceEffect(vec3 color, vec2 uv, vec2 localPos, float dist) {
            // 冰晶网格：用多重 sin 叠加模拟冰裂纹
            float ice1 = sin(uv.x * 25.0 + sin(uv.y * 18.0) * 2.0);
            float ice2 = sin(uv.y * 22.0 + sin(uv.x * 15.0) * 2.5);
            float ice3 = sin((uv.x + uv.y) * 20.0 + sin(uv.x * 12.0 - uv.y * 16.0));

            // 冰裂纹图案（锐利的线条）
            float crack = max(
                max(1.0 - smoothstep(0.0, 0.06, abs(ice1)),
                    1.0 - smoothstep(0.0, 0.06, abs(ice2))),
                1.0 - smoothstep(0.0, 0.05, abs(ice3))
            );

            // 边缘冰霜（靠近卡片边缘更明显）
            float edgeDist = 1.0 - abs(localPos.x);  // 0~1, 0=边缘
            float edgeFrost = smoothstep(0.75, 0.95, 1.0 - edgeDist);

            // 冰晶闪烁
            float sparkle = sin(uTime * 2.0 + uv.x * 40.0) * sin(uTime * 3.0 + uv.y * 35.0);
            sparkle = smoothstep(0.85, 1.0, sparkle) * 0.3;

            // 淡蓝色冰霜叠加
            vec3 iceColor = vec3(0.7, 0.85, 1.0);
            float iceIntensity = (crack * 0.4 + edgeFrost * 0.3 + sparkle);

            // 整体微冷色调
            vec3 coldTint = mix(color, color * vec3(0.9, 0.95, 1.1), 0.3);
            return mix(coldTint, iceColor, iceIntensity);
        }

        // ---- 炫彩效果 ----
        // 基于视角的彩虹渐变 + 光泽闪烁
        vec3 applyHoloEffect(vec3 color, vec2 uv, vec2 localPos) {
            // 视角相关偏移
            float viewShift = localPos.x * 2.0 + uViewAngle.x * 0.8
                            + localPos.y * 1.5 + uViewAngle.y * 0.6;

            // 彩虹色：基于视角的色相旋转
            float hue = fract(viewShift * 0.5 + uTime * 0.1);

            // HSL -> RGB (饱和度 0.3, 亮度 0.7)
            float h = hue * 6.0;
            float c = 0.21;  // 饱和度 * 亮度
            float x = c * (1.0 - abs(mod(h, 2.0) - 1.0));
            float m = 0.595;  // 亮度 - c/2
            vec3 rainbow;
            if (h < 1.0)      rainbow = vec3(c, x, 0.0);
            else if (h < 2.0) rainbow = vec3(x, c, 0.0);
            else if (h < 3.0) rainbow = vec3(0.0, c, x);
            else if (h < 4.0) rainbow = vec3(0.0, x, c);
            else if (h < 5.0) rainbow = vec3(x, 0.0, c);
            else              rainbow = vec3(c, 0.0, x);
            rainbow += m;

            // 光泽条纹
            float stripe = sin(localPos.y * 15.0 + viewShift * 3.0 + uTime * 1.5);
            stripe = smoothstep(0.6, 1.0, stripe) * 0.12;

            // 边缘高光
            float edgeGlow = 1.0 - length(localPos);
            edgeGlow = smoothstep(0.3, 0.8, edgeGlow) * 0.08;

            // 混合：炫彩覆盖约 8%
            return mix(color, rainbow + stripe + edgeGlow, 0.08);
        }

        void main() {
            // 主卡片遮罩（圆角矩形边缘柔化）
            float dist = roundedBoxSDF(vLocalPos, vec2(1.0), uRadius);
            float edgeSoftness = 0.02;
            float mask = 1.0 - smoothstep(-edgeSoftness, edgeSoftness, dist);

            vec4 texColor = texture(uTexture, vUV);

            // Texture alpha participates in card opacity (supports semi-transparent PNGs);
            // uploaded texture is premultiplied alpha: un-premultiply before effect math
            float texAlpha = texColor.a;
            vec3 straightColor = texColor.rgb / max(texAlpha, 0.0001);

            // 应用立绘特效
            vec3 finalColor = straightColor;
            if (uEffect == 1) {
                finalColor = applyIceEffect(finalColor, vUV, vLocalPos, dist);
            } else if (uEffect == 2) {
                finalColor = applyHoloEffect(finalColor, vUV, vLocalPos);
            }

            // Card opacity = rounded mask × texture alpha; card color is output premultiplied
            float cardAlpha = mask * texAlpha;

            // 阴影层：偏移后再次计算圆角矩形 SDF
            vec2 shadowLocalPos = vLocalPos - uShadowOffset;
            float shadowDist = roundedBoxSDF(shadowLocalPos, vec2(1.0), uRadius);
            float shadowMask = 1.0 - smoothstep(-0.05, 0.1, shadowDist);

            // Card composited over the shadow (premultiplied-alpha "over"), scaled by uAlpha
            float shadowAlpha = shadowMask * uShadowColor.a;
            vec3 color = finalColor * cardAlpha + uShadowColor.rgb * shadowAlpha * (1.0 - cardAlpha);
            float alpha = cardAlpha + shadowAlpha * (1.0 - cardAlpha);
            fragColor = vec4(color * uAlpha, alpha * uAlpha);
        }
        """.trimIndent()

    /** 背景顶点着色器：全屏四边形，不做变换。 */
    val backgroundVertex =
        """
        #version 300 es
        precision highp float;

        uniform mat4 uMVP;
        uniform vec2 uParallax;

        layout(location = 0) in vec2 aPosition;
        layout(location = 1) in vec2 aUV;

        out vec2 vUV;

        void main() {
            gl_Position = uMVP * vec4(aPosition, 0.0, 1.0);
            vUV = aUV;
        }
        """.trimIndent()

    /** 背景片段着色器：简单纹理采样 + 整体透明度。 */
    val backgroundFragment =
        """
        #version 300 es
        precision highp float;

        uniform sampler2D uTexture;
        uniform float uAlpha;

        in vec2 vUV;
        out vec4 fragColor;

        void main() {
            vec4 texColor = texture(uTexture, vUV);
            // Texture is premultiplied alpha: scale rgb and a by uAlpha so the
            // (ONE, ONE_MINUS_SRC_ALPHA) blend fades the layer as a whole
            fragColor = vec4(texColor.rgb * uAlpha, texColor.a * uAlpha);
        }
        """.trimIndent()

    // Inverse height-field ray intersection: first hit resolves foreground occlusion.
    val spatialVertex = """
        #version 300 es
        precision highp float;
        out vec2 vUV;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUV = vec2(p.x, 1.0 - p.y);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """.trimIndent()

    val spatialFragment = """
        #version 300 es
        precision highp float;
        uniform highp sampler2D uImage;
        uniform highp sampler2D uDepth;
        uniform vec2 uCover;
        uniform vec2 uMotion;
        uniform vec2 uCamera;
        uniform vec2 uDepthRange;
        uniform vec2 uTexel;
        uniform float uSurfaceTolerance;
        uniform int uShowFallback;
        uniform int uMeasureFallback;
        uniform int uProbe;
        uniform vec2 uProbeUV[3];
        uniform sampler2D uClock;
        uniform sampler2D uMask;
        uniform int uClockEnabled;
        in vec2 vUV;
        out vec4 fragColor;
        bool validUV(vec2 uv) {
            return all(greaterThanEqual(uv, uTexel)) && all(lessThanEqual(uv, 1.0-uTexel));
        }
        float depthAt(vec2 uv) {
            float span = uDepthRange.y - uDepthRange.x;
            if (span < 1.0 / 255.0) return 0.5;
            ivec2 size = textureSize(uDepth, 0);
            ivec2 exact = clamp(ivec2(clamp(uv, uTexel, 1.0-uTexel) * vec2(size)), ivec2(0), size-1);
            // Use identical integer addressing for visibility and source color.
            float value = texelFetch(uDepth, exact, 0).r;
            return clamp((value-uDepthRange.x) / max(0.15, span), 0.0, 1.0);
        }
        vec3 imageAt(vec2 uv) {
            ivec2 size = textureSize(uDepth, 0);
            vec2 p = clamp(uv, uTexel, 1.0-uTexel) * vec2(size) - 0.5;
            ivec2 a = clamp(ivec2(floor(p)), ivec2(0), size-1);
            ivec2 b = min(a+1, size-1);
            float d0 = texelFetch(uDepth, a, 0).r;
            float d1 = texelFetch(uDepth, ivec2(b.x,a.y), 0).r;
            float d2 = texelFetch(uDepth, ivec2(a.x,b.y), 0).r;
            float d3 = texelFetch(uDepth, b, 0).r;
            float range = max(max(d0,d1),max(d2,d3))-min(min(d0,d1),min(d2,d3));
            if (range > max(0.15, uDepthRange.y-uDepthRange.x) * 0.12) {
                // Depth-aware bilinear reconstruction. Depth and photo may have
                // different resolutions: sample photo in normalized coordinates.
                float target = depthAt(uv);
                vec2 f = fract(p);
                vec3 sum = vec3(0.0);
                float weight = 0.0;
                for (int y = 0; y < 2; ++y) for (int x = 0; x < 2; ++x) {
                    ivec2 cell = ivec2(x == 0 ? a.x : b.x, y == 0 ? a.y : b.y);
                    vec2 q = (vec2(cell) + 0.5) / vec2(size);
                    float w = (x == 0 ? 1.0-f.x : f.x) * (y == 0 ? 1.0-f.y : f.y);
                    if (abs(depthAt(q)-target) <= 0.03) {
                        sum += texture(uImage, q).rgb * w;
                        weight += w;
                    }
                }
                return sum / max(weight, 0.000001);
            }
            return texture(uImage, uv).rgb;
        }
        void main() {
            // Camera offset moves even the far plane. Relative depth remains continuous.
            vec2 screenUV = uProbe == 1 ? uProbeUV[int(gl_FragCoord.y) % 3] : vUV;
            vec2 base = (screenUV - 0.5) * uCover + 0.5 - uCamera;
            vec2 uv = base;
            bool hit = false;
            // Traverse the piecewise-constant depth field in source texel order.
            // A fixed z march skips thin foreground cells at strong motion.
            vec2 size = vec2(textureSize(uDepth, 0));
            float enter = 0.0;
            float leave = 1.0;
            for (int axis = 0; axis < 2; ++axis) {
                if (abs(uMotion[axis]) < 0.0000001) {
                    if (base[axis] < uTexel[axis] || base[axis] > 1.0-uTexel[axis]) leave = -1.0;
                } else {
                    float t0 = (base[axis] - (1.0-uTexel[axis])) / uMotion[axis];
                    float t1 = (base[axis] - uTexel[axis]) / uMotion[axis];
                    enter = max(enter, min(t0,t1));
                    leave = min(leave, max(t0,t1));
                }
            }
            float hi = leave;
            float bestError = 2.0;
            float bestDepth = 2.0;
            vec2 direction = sign(uMotion);
            for (int i = 0; i < 512; ++i) {
                if (hi < enter) break;
                vec2 start = base - uMotion * hi;
                // Choose the cell entered as z decreases, including exact grid ties.
                ivec2 cell = clamp(ivec2(floor(start * size + sign(uMotion)*0.0001)),
                                   ivec2(0), ivec2(size)-1);
                vec2 center = (vec2(cell)+0.5)/size;
                float lo = enter;
                for (int axis = 0; axis < 2; ++axis) {
                    if (abs(uMotion[axis]) > 0.0000001) {
                        float boundary = (float(cell[axis]) + (direction[axis] > 0.0 ? 1.0 : 0.0))/size[axis];
                        float crossing = (base[axis]-boundary)/uMotion[axis];
                        lo = max(lo, min(hi, crossing));
                    }
                }
                float depth = depthAt(center);
                vec2 intersection = base-uMotion*depth;
                // Exact cell membership rejects vertical depth walls and preserves
                // first-hit foreground visibility. Tolerance is not enlarged.
                if (depth >= lo && depth <= hi && validUV(intersection) &&
                    all(equal(ivec2(floor(intersection*size)), cell)) &&
                    abs(depthAt(intersection)-depth) <= uSurfaceTolerance) {
                    uv = intersection; hit = true; break;
                }
                float z = clamp(depth, lo, hi);
                float error = abs(depth-z);
                // A disoccluded pixel must not extend the foreground silhouette.
                // Prefer an observed farther layer, then its closest ray interval.
                if (depth < bestDepth || (depth == bestDepth && error < bestError)) {
                    bestDepth = depth;
                    bestError = error;
                    // Stay inside the actual visited source cell, on the same ray.
                    uv = base-uMotion*mix(lo,hi,0.5);
                }
                if (lo <= enter) break;
                hi = lo;
            }
            // Exhausted traversal / disocclusion remain failures even when a
            // bounded best source sample is displayed. No synthetic background.
            // Diagnostic-only pass: red=failed ray intersection, black=hit.
            // The renderer reads this mask back only when the user requests a measurement.
            if (uMeasureFallback == 1) {
                fragColor = vec4(hit ? 0.0 : 1.0, 0.0, 0.0, 1.0);
                return;
            }
            vec3 color = imageAt(clamp(uv, uTexel, 1.0-uTexel));
            if (uClockEnabled == 1) {
                vec4 clock = texture(uClock, vUV);
                float mask = texture(uMask, uv).r;
                // GLUtils uploads premultiplied clock pixels. Both image and mask
                // use the same ray intersection UV, avoiding displaced contours.
                float visible = 1.0 - mask;
                color = color * (1.0 - clock.a * visible) + clock.rgb * visible;
            }
            if (uShowFallback == 1 && !hit) color = vec3(1.0, 0.0, 1.0);
            fragColor = vec4(color, 1.0);
            if (uProbe == 1) {
                // Same production ray above; encode its actual intersection, not a formula.
                if (gl_FragCoord.y < 3.0) {
                    vec2 q = floor(clamp(uv, 0.0, 1.0) * 65535.0 + 0.5);
                    fragColor = vec4(floor(q.x/256.0), mod(q.x,256.0), floor(q.y/256.0), mod(q.y,256.0))/255.0;
                } else fragColor = vec4(depthAt(uv), hit ? 0.0 : 1.0, 0.0, 1.0);
            }
        }
        """.trimIndent()
}
