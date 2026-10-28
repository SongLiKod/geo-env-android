package com.geoenv.browser.gecko

/**
 * 指纹 Hook JS 脚本生成器（需求 3.3）。
 *
 * 注入时机：Gecko 页面内容脚本（`document_start`），先于页面任何脚本执行。
 * 覆盖：Canvas / WebGL / Audio / WebRTC / UA-CH / 屏幕 / 语言 / 时区 / 插件 / 硬件并发。
 *
 * 开发规范 5：Hook 脚本版本跟随 Gecko 内核版本管理 —— [HOOK_VERSION] 内嵌 Gecko 版本号。
 */
object HookScript {

    /** Hook 脚本版本（跟随内核） */
    const val HOOK_VERSION = "158.0.20261021001945-1"

    /**
     * 生成注入脚本。
     * @param fp 指纹配置
     * @return 完整 JS 源码（在页面 JS 上下文执行）
     */
    fun build(fp: FingerprintConfig): String = buildString {
        appendLine("// GeoEnv fingerprint hook v$HOOK_VERSION")
        appendLine("// env-generated, do not edit")
        appendLine("(function(){")
        appendLine("'use strict';")
        appendLine("if (window.__geoEnvHooked) return;")
        appendLine("window.__geoEnvHooked = true;")
        appendLine("var CFG = ${configJson(fp)};")

        appendLine(screenJs(fp))
        appendLine(languageJs(fp))
        appendLine(navigatorJs(fp))
        appendLine(webglJs(fp))
        appendLine(canvasJs(fp))
        appendLine(audioJs(fp))
        appendLine(webrtcJs(fp))
        appendLine(pluginJs(fp))
        appendLine(clientHintsJs(fp))

        if (fp.extraJs.isNotBlank()) {
            appendLine("// extra user script")
            appendLine(runCatching { fp.extraJs }.getOrDefault(""))
        }

        appendLine("})();")
    }

    /** 注入到页面的配置对象（紧凑 JSON） */
    private fun configJson(fp: FingerprintConfig): String {
        val sb = StringBuilder("{")
        fun put(k: String, v: Any) { sb.append("\"$k\":").append(v).append(",") }
        put("sw", fp.screenWidth); put("sh", fp.screenHeight)
        put("aw", fp.availWidth); put("ah", fp.availHeight)
        put("dpr", fp.pixelRatio.toString()); put("dpi", fp.screenDpi)
        put("cd", fp.colorDepth.toString())
        put("model", q(fp.deviceModel)); put("mfr", q(fp.deviceManufacturer))
        put("tz", q(fp.timezone))
        put("lang", q(fp.resolveNavigatorLanguage()))
        put("langs", fp.languages.joinToString(",", "[", "]") { q(it) })
        put("canvasMode", q(fp.canvasMode))
        put("canvasSeed", fp.canvasNoiseSeed.toString())
        put("canvasStrength", fp.canvasNoiseStrength.toString())
        put("webglVendor", q(fp.webglVendor)); put("webglRenderer", q(fp.webglRenderer))
        put("webglMask", fp.webglMaskDebugInfo.toString()); put("webglMode", q(fp.webglMode))
        put("audioMode", q(fp.audioMode)); put("audioSeed", fp.audioNoiseSeed.toString())
        put("rtc", q(fp.webrtcPolicy)); put("rtcBlockLocal", fp.webrtcBlockLocalCandidates.toString())
        put("pluginMode", q(fp.pluginMode))
        put("plugins", fp.customPlugins.joinToString(",", "[", "]") { p ->
            "{\"name\":${q(p.name)},\"filename\":${q(p.filename)},\"description\":${q(p.description)}}"
        })
        put("cores", fp.hardwareConcurrency.toString())
        put("touch", fp.maxTouchPoints.toString())
        put("vendor", q(fp.vendor))
        put("dnt", fp.doNotTrack.toString())
        put("chPlatform", q(fp.chPlatform))
        put("chPlatformVersion", q(fp.chPlatformVersion))
        put("chFullVersion", q(fp.chFullVersion))
        put("chModel", q(fp.chModel))
        put("chArch", q(fp.chArchitecture))
        put("chBitness", q(fp.chBitness))
        put("chDeviceMemory", fp.chDeviceMemory.toString())
        put("chFullVersionList", q(fp.chUaFullVersionList))
        if (sb.endsWith(",")) sb.deleteAt(sb.length - 1)
        sb.append("}")
        return sb.toString()
    }

    private fun q(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    // ---------------------------------------------------------------- 屏幕
    private fun screenJs(fp: FingerprintConfig): String = """
        try {
          var sw = ${fp.screenWidth}, sh = ${fp.screenHeight};
          var aw = ${fp.availWidth}, ah = ${fp.availHeight};
          var dpr = ${fp.pixelRatio}, cd = ${fp.colorDepth};
          function def(obj, prop, value, writable) {
            try {
              Object.defineProperty(obj, prop, {
                configurable: true,
                enumerable: true,
                get: function(){ return value; },
                set: function(){}
              });
            } catch(e) {}
          }
          def(window.screen, 'width', sw);
          def(window.screen, 'height', sh);
          def(window.screen, 'availWidth', aw);
          def(window.screen, 'availHeight', ah);
          def(window.screen, 'colorDepth', cd);
          def(window.screen, 'pixelDepth', cd);
          def(window, 'devicePixelRatio', dpr);
          def(window, 'innerWidth', sw);
          def(window, 'innerHeight', sh);
          def(window, 'outerWidth', sw);
          def(window, 'outerHeight', sh);
        } catch(e) {}
    """.trimIndent()

    // ---------------------------------------------------------------- 语言 / 时区
    private fun languageJs(fp: FingerprintConfig): String = """
        try {
          var LANG = ${q(fp.resolveNavigatorLanguage())};
          var LANGS = ${fp.languages.joinToString(",", "[", "]") { q(it) }};
          var TZ = ${q(fp.timezone)};
          function def(obj, prop, value) {
            try {
              Object.defineProperty(obj, prop, {
                configurable: true, enumerable: true,
                get: function(){ return value; }, set: function(){}
              });
            } catch(e) {}
          }
          def(navigator, 'language', LANG);
          def(navigator, 'languages', Object.freeze(LANGS.slice()));
          def(navigator, 'userLanguage', LANG);
          // Intl 时区：覆盖 resolvedOptions
          try {
            var origResolved = Intl.DateTimeFormat.prototype.resolvedOptions;
            Intl.DateTimeFormat.prototype.resolvedOptions = function() {
              var r = origResolved.call(this);
              try { r.timeZone = TZ; } catch(e) {}
              try { r.locale = LANG; } catch(e) {}
              return r;
            };
          } catch(e) {}
          // Date.prototype.getTimezoneOffset 与 TZ 对齐（分钟，东负西正）
          try {
            var offsetMinutes = (function(){
              try {
                var d = new Date();
                var utc = new Date(d.toLocaleString('en-US', {timeZone:'UTC'}));
                var loc = new Date(d.toLocaleString('en-US', {timeZone:TZ}));
                return Math.round((utc - loc) / 60000);
              } catch(e) { return null; }
            })();
            if (offsetMinutes !== null && !isNaN(offsetMinutes)) {
              var origGetTz = Date.prototype.getTimezoneOffset;
              Date.prototype.getTimezoneOffset = function(){ return offsetMinutes; };
            }
          } catch(e) {}
        } catch(e) {}
    """.trimIndent()

    // ---------------------------------------------------------------- navigator 杂项
    private fun navigatorJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          function def(obj, prop, value) {
            try {
              Object.defineProperty(obj, prop, {
                configurable: true, enumerable: true,
                get: function(){ return value; }, set: function(){}
              });
            } catch(e) {}
          }
        """.trimIndent())
        if (fp.hardwareConcurrency > 0) {
            appendLine("  def(navigator, 'hardwareConcurrency', ${fp.hardwareConcurrency});")
        }
        if (fp.maxTouchPoints >= 0) {
            appendLine("  def(navigator, 'maxTouchPoints', ${fp.maxTouchPoints});")
        }
        if (fp.vendor.isNotBlank()) {
            appendLine("  def(navigator, 'vendor', ${q(fp.vendor)});")
            appendLine("  def(navigator, 'vendorSub', '');")
        }
        if (fp.doNotTrack) {
            appendLine("  def(navigator, 'doNotTrack', '1');")
        }
        if (fp.deviceModel.isNotBlank()) {
            appendLine("  def(navigator, 'device', ${q(fp.deviceModel)});")
        }
        appendLine("""
          // platform 与 UA 对齐
          try {
            var uaP = ${q(fp.uaPlatform)};
            var plat = uaP.indexOf('Android') >= 0 ? 'Linux armv8l'
                     : uaP.indexOf('Windows') >= 0 ? 'Win32'
                     : uaP.indexOf('Mac OS X') >= 0 ? 'MacIntel' : 'Linux x86_64';
            def(navigator, 'platform', plat);
          } catch(e) {}
        """.trimIndent())
        appendLine("} catch(e) {}")
    }

    // ---------------------------------------------------------------- WebGL
    private fun webglJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          var VENDOR = ${q(fp.webglVendor)};
          var RENDERER = ${q(fp.webglRenderer)};
          var MASK = ${fp.webglMaskDebugInfo};
          var MODE = ${q(fp.webglMode)};
          function patchCtx(ctx) {
            if (!ctx || ctx.__geoPatched) return ctx;
            ctx.__geoPatched = true;
            try {
              var origGetParameter = ctx.getParameter.bind(ctx);
              ctx.getParameter = function(p) {
                try {
                  // 0x9245 = UNMASKED_VENDOR_WEBGL, 0x9246 = UNMASKED_RENDERER_WEBGL
                  if (MASK && (p === 0x9245 || p === 0x9246)) {
                    return p === 0x9245 ? VENDOR : RENDERER;
                  }
                  if (p === 0x1F01 /* VENDOR */ ) return VENDOR;
                  if (p === 0x1F02 /* RENDERER */) return RENDERER;
                } catch(e) {}
                return origGetParameter(p);
              };
            } catch(e) {}
            if (MODE !== 'OFF') {
              try {
                var origGetExtension = ctx.getExtension.bind(ctx);
                ctx.getExtension = function(name) {
                  var ext = origGetExtension(name);
                  if (name === 'WEBGL_debug_renderer_info' && MASK) {
                    // 返回对象但 getParameter 已被拦截
                  }
                  return ext;
                };
              } catch(e) {}
            }
            return ctx;
          }
          var origGetContext = HTMLCanvasElement.prototype.getContext;
          HTMLCanvasElement.prototype.getContext = function(type) {
            var ctx = origGetContext.apply(this, arguments);
            if (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl') {
              return patchCtx(ctx);
            }
            return ctx;
          };
        } catch(e) {}
        """.trimIndent())
    }

    // ---------------------------------------------------------------- Canvas
    private fun canvasJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          var MODE = ${q(fp.canvasMode)};
          var SEED = ${fp.canvasNoiseSeed} >>> 0;
          var STRENGTH = ${fp.canvasNoiseStrength};
          if (MODE !== 'OFF') {
            // 确定性 PRNG：同一环境每次注入结果一致（可复现）
            function prng(x) {
              x = (x ^ 61) ^ (x >>> 16);
              x = (x + (x << 3)) | 0;
              x = x ^ (x >>> 4);
              x = Math.imul(x, 0x27d4eb2d);
              x = x ^ (x >>> 15);
              return (x >>> 0) / 4294967296;
            }
            function noiseCanvas(canvas) {
              try {
                if (MODE === 'BLOCK') {
                  // 读取时返回全白/全透明，页面无法拿到特征像素
                  var w = canvas.width || 1, h = canvas.height || 1;
                  var c = document.createElement('canvas');
                  c.width = w; c.height = h;
                  var cx = c.getContext('2d');
                  if (cx) { cx.fillStyle = '#fff'; cx.fillRect(0,0,w,h); }
                  return c;
                }
                var w = canvas.width, h = canvas.height;
                if (!w || !h || w * h > 4000000) return canvas;
                var ctx = canvas.getContext('2d');
                if (!ctx) return canvas;
                var img = ctx.getImageData(0, 0, w, h);
                var d = img.data;
                var s = SEED;
                for (var i = 0; i < d.length; i += 4) {
                  s = (s + 0x9e3779b9) >>> 0;
                  var r = prng(s);
                  if (r < STRENGTH) {
                    // 只扰动 alpha 低 2 位与 RGB 各 1 位，视觉无差异但哈希改变
                    d[i]     = (d[i]     & 0xFE) | (prng(s ^ 0x1) > 0.5 ? 1 : 0);
                    d[i + 1] = (d[i + 1] & 0xFE) | (prng(s ^ 0x2) > 0.5 ? 1 : 0);
                    d[i + 2] = (d[i + 2] & 0xFE) | (prng(s ^ 0x3) > 0.5 ? 1 : 0);
                  }
                }
                ctx.putImageData(img, 0, 0);
              } catch(e) {}
              return canvas;
            }
            var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
            HTMLCanvasElement.prototype.toDataURL = function() {
              noiseCanvas(this);
              return origToDataURL.apply(this, arguments);
            };
            var origToBlob = HTMLCanvasElement.prototype.toBlob;
            if (origToBlob) {
              HTMLCanvasElement.prototype.toBlob = function(cb) {
                noiseCanvas(this);
                return origToBlob.apply(this, arguments);
              };
            }
            var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
            CanvasRenderingContext2D.prototype.getImageData = function(x, y, w, h) {
              var img = origGetImageData.apply(this, arguments);
              if (MODE === 'NOISE') {
                try {
                  var d = img.data, s = SEED ^ 0xabcdef;
                  for (var i = 0; i < d.length; i += 4) {
                    s = (s + 0x85ebca6b) >>> 0;
                    if (prng(s) < STRENGTH) {
                      d[i] = (d[i] & 0xFE);
                    }
                  }
                } catch(e) {}
              }
              return img;
            };
            // OffscreenCanvas（支持时）
            if (typeof OffscreenCanvas !== 'undefined') {
              try {
                var otd = OffscreenCanvas.prototype.convertToBlob;
                if (otd) {
                  OffscreenCanvas.prototype.convertToBlob = function() {
                    return otd.apply(this, arguments);
                  };
                }
              } catch(e) {}
            }
          }
        } catch(e) {}
        """.trimIndent())
    }

    // ---------------------------------------------------------------- Audio
    private fun audioJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          var MODE = ${q(fp.audioMode)};
          var SEED = ${fp.audioNoiseSeed} >>> 0;
          if (MODE !== 'OFF') {
            function aprng(x) {
              x = (x ^ 61) ^ (x >>> 16);
              x = (x + (x << 3)) | 0;
              x = x ^ (x >>> 4);
              x = Math.imul(x, 0x27d4eb2d);
              x = x ^ (x >>> 15);
              return (x >>> 0) / 4294967296;
            }
            var origCreateAnalyser = AudioContext.prototype.createAnalyser;
            if (origCreateAnalyser) {
              AudioContext.prototype.createAnalyser = function() {
                var an = origCreateAnalyser.call(this);
                try {
                  var origGetFloat = an.getFloatFrequencyData.bind(an);
                  an.getFloatFrequencyData = function(arr) {
                    origGetFloat(arr);
                    try {
                      var s = SEED;
                      for (var i = 0; i < arr.length; i++) {
                        s = (s + 0x9e3779b9) >>> 0;
                        if (aprng(s) < 0.05 && isFinite(arr[i])) {
                          arr[i] = arr[i] + (aprng(s ^ 0x55) - 0.5) * 0.0001;
                        }
                      }
                    } catch(e) {}
                  };
                  var origGetByte = an.getByteFrequencyData.bind(an);
                  an.getByteFrequencyData = function(arr) {
                    origGetByte(arr);
                    try {
                      var s = SEED ^ 0x1234;
                      for (var i = 0; i < arr.length; i++) {
                        s = (s + 0x85ebca6b) >>> 0;
                        if (aprng(s) < 0.05) {
                          arr[i] = Math.max(0, Math.min(255, arr[i] + ((aprng(s) > 0.5) ? 1 : -1)));
                        }
                      }
                    } catch(e) {}
                  };
                } catch(e) {}
                return an;
              };
            }
            var origDecode = (window.AudioContext || window.webkitAudioContext);
            if (origDecode && origDecode.prototype.decodeAudioData) {
              var od = origDecode.prototype.decodeAudioData;
              origDecode.prototype.decodeAudioData = function(buf) {
                return od.apply(this, arguments).then(function(audioBuffer) {
                  try {
                    var ch = audioBuffer.getChannelData(0);
                    var s = SEED;
                    var n = Math.min(ch.length, 4096);
                    for (var i = 0; i < n; i++) {
                      s = (s + 0x9e3779b9) >>> 0;
                      if (aprng(s) < 0.02) {
                        ch[i] = ch[i] + (aprng(s ^ 0x77) - 0.5) * 1e-7;
                      }
                    }
                  } catch(e) {}
                  return audioBuffer;
                });
              };
            }
          }
        } catch(e) {}
        """.trimIndent())
    }

    // ---------------------------------------------------------------- WebRTC
    private fun webrtcJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          var POLICY = ${q(fp.webrtcPolicy)};
          var BLOCK_LOCAL = ${fp.webrtcBlockLocalCandidates};
          if (POLICY === 'DISABLE') {
            // 彻底移除 WebRTC 能力，页面调用即抛错（与 Firefox 严格模式一致）
            try {
              Object.defineProperty(window, 'RTCPeerConnection', {
                configurable: true, enumerable: false,
                get: function(){ return undefined; }, set: function(){}
              });
              Object.defineProperty(window, 'webkitRTCPeerConnection', {
                configurable: true, enumerable: false,
                get: function(){ return undefined; }, set: function(){}
              });
              Object.defineProperty(window, 'RTCSessionDescription', {
                configurable: true, enumerable: false,
                get: function(){ return undefined; }, set: function(){}
              });
              Object.defineProperty(window, 'RTCIceCandidate', {
                configurable: true, enumerable: false,
                get: function(){ return undefined; }, set: function(){}
              });
            } catch(e) {}
          } else if (BLOCK_LOCAL && window.RTCPeerConnection) {
            var OrigRTC = window.RTCPeerConnection;
            var Patched = function(cfg, constraints) {
              if (cfg && typeof cfg === 'object') {
                cfg = Object.assign({}, cfg);
                // 屏蔽本地候选，防止泄露内网 IP
                cfg.iceCandidatePoolSize = 0;
                if (!cfg.iceServers) cfg.iceServers = [];
              }
              var pc = new OrigRTC(cfg, constraints);
              try {
                var origCreateOffer = pc.createOffer.bind(pc);
                pc.createOffer = function() {
                  return origCreateOffer.apply(this, arguments);
                };
                var origAddIce = pc.addIceCandidate.bind(pc);
                pc.addIceCandidate = function(cand) {
                  try {
                    if (cand && cand.candidate && / typ host /.test(cand.candidate)) {
                      return Promise.resolve();
                    }
                  } catch(e) {}
                  return origAddIce.apply(this, arguments);
                };
                var origCreateAnswer = pc.createAnswer.bind(pc);
                pc.createAnswer = function() {
                  return origCreateAnswer.apply(this, arguments);
                };
              } catch(e) {}
              return pc;
            };
            Patched.prototype = OrigRTC.prototype;
            try {
              window.RTCPeerConnection = Patched;
              window.webkitRTCPeerConnection = Patched;
            } catch(e) {}
          }
        } catch(e) {}
        """.trimIndent())
    }

    // ---------------------------------------------------------------- 插件
    private fun pluginJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          var MODE = ${q(fp.pluginMode)};
          var CUSTOM = ${fp.customPlugins.joinToString(",", "[", "]") { p ->
              "{\"name\":${q(p.name)},\"filename\":${q(p.filename)},\"description\":${q(p.description)}}"
          }};
          function buildPluginArray(list) {
            var arr = new PluginArray();
            list.forEach(function(p) {
              try {
                var plugin = new Plugin();
                Object.defineProperty(plugin, 'name', {value: p.name, enumerable: true});
                Object.defineProperty(plugin, 'filename', {value: p.filename, enumerable: true});
                Object.defineProperty(plugin, 'description', {value: p.description, enumerable: true});
                Object.defineProperty(plugin, 'length', {value: 0, enumerable: true});
                arr[Symbol.iterator] = Array.prototype[Symbol.iterator];
                arr.namedItem = function(n){ return n === p.name ? plugin : null; };
                arr.item = function(i){ return i === 0 ? plugin : null; };
                Object.defineProperty(arr, 'length', {value: list.length, enumerable: true});
              } catch(e) {}
            });
            return arr;
          }
          if (MODE === 'EMPTY') {
            Object.defineProperty(navigator, 'plugins', {
              configurable: true, enumerable: true,
              get: function(){ return buildPluginArray([]); }, set: function(){}
            });
            Object.defineProperty(navigator, 'mimeTypes', {
              configurable: true, enumerable: true,
              get: function(){ return {length: 0, item: function(){return null;}, namedItem: function(){return null;}}; },
              set: function(){}
            });
          } else if (MODE === 'CUSTOM' && CUSTOM.length) {
            Object.defineProperty(navigator, 'plugins', {
              configurable: true, enumerable: true,
              get: function(){ return buildPluginArray(CUSTOM); }, set: function(){}
            });
          }
        } catch(e) {}
        """.trimIndent())
    }

    // ---------------------------------------------------------------- UA-CH
    private fun clientHintsJs(fp: FingerprintConfig): String = buildString {
        appendLine("""
        try {
          function def(obj, prop, value) {
            try {
              Object.defineProperty(obj, prop, {
                configurable: true, enumerable: true,
                get: function(){ return value; }, set: function(){}
              });
            } catch(e) {}
          }
          var UA = ${q(fp.resolveUserAgent())};
          def(navigator, 'userAgent', UA);
          def(navigator, 'appVersion', UA.substring(8));
          var CH = {
            platform: ${q(fp.chPlatform)},
            platformVersion: ${q(fp.chPlatformVersion)},
            architecture: ${q(fp.chArchitecture)},
            bitness: ${q(fp.chBitness)},
            model: ${q(fp.chModel)},
            mobile: ${fp.uaMobile},
            uaFullVersion: ${q(fp.chFullVersion)},
            fullVersionList: ${fp.chUaFullVersionList.split("; ").joinToString(",", "[", "]") { q(it) }},
            platformVersionList: [${q(fp.chPlatformVersion)}],
            wow64: false
          };
          if (navigator.userAgentData) {
            try {
              var origToJSON = navigator.userAgentData.toJSON;
              navigator.userAgentData.toJSON = function(){ return CH; };
            } catch(e) {}
            try {
              Object.defineProperty(navigator.userAgentData, 'brands', {
                configurable: true,
                get: function(){
                  return [
                    {brand: 'Chromium', version: ${fp.uaChromeVersion}},
                    {brand: 'Not_A Brand', version: 24}
                  ];
                }
              });
              Object.defineProperty(navigator.userAgentData, 'mobile', {
                configurable: true, get: function(){ return ${fp.uaMobile}; }
              });
              Object.defineProperty(navigator.userAgentData, 'platform', {
                configurable: true, get: function(){ return ${q(fp.chPlatform)}; }
              });
            } catch(e) {}
            try {
              var origGetHighEntropy = navigator.userAgentData.getHighEntropyValues;
              if (origGetHighEntropy) {
                navigator.userAgentData.getHighEntropyValues = function(hints) {
                  var self = this;
                  return origGetHighEntropy.call(self, hints).then(function(res) {
                    var out = Object.assign({}, res);
                    hints.forEach(function(h) {
                      if (CH[h] !== undefined) out[h] = CH[h];
                    });
                    out.brands = CH.fullVersionList.map(function(b){
                      var parts = b.split('; ');
                      return {brand: parts[0], version: parseInt((parts[1]||'0').split('.')[0], 10) || 0};
                    });
                    out.mobile = CH.mobile;
                    out.platform = CH.platform;
                    return out;
                  });
                };
              }
            } catch(e) {}
          }
        } catch(e) {}
        """.trimIndent())
    }
}
