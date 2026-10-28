#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成降级用静态占位扩展 assets/extensions/geoenv/base.xpi。

SlotEngine.installExtension 在动态生成 xpi 安装失败时，会回退安装
APK assets 中的静态 base.xpi（resource://android/assets/extensions/geoenv/base.xpi），
仅保证 native port 通信（HAR/Cookie/eval/弹窗）可用，**不含环境指纹 Hook**。

内容与 ExtensionFactory 中的 manifest / background.js / CAPTCHA_SCAN_JS 保持一致，
content.js 仅保留验证码扫描段（无环境配置，故无指纹 Hook）。

用法：python3 scripts/gen_base_xpi.py
"""
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/src/main/assets/extensions/geoenv/base.xpi")

EXTENSION_ID = "geoenv-helper@geoenv.local"
NATIVE_APP = "geoenv"
VERSION = "1.0.0"

MANIFEST = """{
  "manifest_version": 2,
  "name": "GeoEnv Helper",
  "version": "1.0.0",
  "description": "GeoEnv fingerprint / HAR / cookie bridge (internal)",
  "browser_specific_settings": {
    "gecko": {
      "id": "geoenv-helper@geoenv.local",
      "strict_min_version": "108.0"
    }
  },
  "permissions": ["webRequest", "cookies", "tabs", "<all_urls>", "storage"],
  "background": { "scripts": ["background.js"] },
  "content_scripts": [
    {
      "matches": ["<all_urls>"],
      "js": ["content.js"],
      "run_at": "document_start",
      "all_frames": true,
      "match_about_blank": true
    }
  ],
  "web_accessible_resources": ["content.js"]
}"""

# 与 ExtensionFactory.BACKGROUND_JS 一致（NATIVE 内联为 "geoenv"）
BACKGROUND_JS = r"""'use strict';
/* GeoEnv helper background — native port bridge (HAR / cookies / eval) */
const NATIVE = "geoenv";
let port = null;
let seq = 1;
const har = { recording: false, entries: [], inFlight: {}, dropped: 0 };
const HAR_CAP = 50000;

function connect() {
  if (port) return;
  try {
    port = browser.runtime.connectNative(NATIVE);
    port.onMessage.addListener(onAppMessage);
    port.onDisconnect.addListener(() => { port = null; setTimeout(connect, 1500); });
  } catch (e) { port = null; setTimeout(connect, 2000); }
}
function post(obj) {
  if (!port) { connect(); setTimeout(() => { try { if (port) port.postMessage(obj); } catch (e) {} }, 600); return; }
  try { port.postMessage(obj); } catch (e) { port = null; connect(); }
}
function reply(id, payload) { payload.id = id; post(payload); }

connect();

async function onAppMessage(msg) {
  if (!msg || !msg.id || !msg.cmd) return;
  try {
    switch (msg.cmd) {
      case 'startHar':
        har.recording = true; har.entries = []; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'stopHar':
        har.recording = false; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'getHar': {
        const off = msg.offset | 0, lim = msg.limit | 0 || 100;
        reply(msg.id, { ok: true, entries: har.entries.slice(off, off + lim), total: har.entries.length });
        break;
      }
      case 'clearHar':
        har.entries = []; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'eval': {
        let tabId = msg.tabId;
        if (!tabId) {
          const tabs = await browser.tabs.query({ active: true, currentWindow: true });
          tabId = tabs[0] && tabs[0].id;
        }
        if (!tabId) { reply(msg.id, { ok: false, error: 'no_tab' }); break; }
        const r = await browser.tabs.executeScript(tabId, { code: String(msg.code || '') });
        reply(msg.id, { ok: true, result: (r && r.length ? r[0] : null) });
        break;
      }
      case 'getCookies': {
        const cs = await browser.cookies.getAll({ url: msg.url });
        reply(msg.id, {
          ok: true,
          cookies: cs.map(c => ({
            name: c.name, value: c.value, domain: c.domain, path: c.path,
            expires: c.expirationDate, httpOnly: c.httpOnly, secure: c.secure,
            sameSite: c.sameSite, storeId: c.storeId, hostOnly: c.hostOnly
          }))
        });
        break;
      }
      case 'setCookie': {
        const r = await browser.cookies.set(msg.cookie || {});
        reply(msg.id, { ok: !!r });
        break;
      }
      case 'ping':
        reply(msg.id, { ok: true, recording: har.recording, buffered: har.entries.length });
        break;
      default:
        reply(msg.id, { ok: false, error: 'unknown_cmd' });
    }
  } catch (e) {
    reply(msg.id, { ok: false, error: String((e && e.message) || e) });
  }
}

/* ----------------------------- HAR (webRequest) ----------------------------- */
function hdrs(list) { return (list || []).map(h => ({ name: h.name, value: h.value })); }
function formDataToParams(fd) {
  const params = [];
  for (const k in fd) {
    const v = fd[k];
    (Array.isArray(v) ? v : [v]).forEach(x => params.push({ name: k, value: String(x) }));
  }
  return params;
}
browser.webRequest.onBeforeRequest.addListener(d => {
  if (!har.recording) return;
  if (har.entries.length >= HAR_CAP) { har.dropped++; return; }
  const e = {
    startedDateTime: new Date(d.timeStamp).toISOString(),
    time: 0,
    request: { method: d.method, url: d.url, headers: [], queryString: [], cookies: [], postData: null, httpVersion: "HTTP/1.1" },
    response: { status: 0, statusText: "", headers: [], cookies: [], content: { size: 0, mimeType: "" }, httpVersion: "HTTP/1.1", redirectURL: "" },
    cache: {}, timings: { blocked: 0, dns: -1, connect: -1, send: 0, wait: 0, receive: 0, ssl: -1 },
    _type: d.type, _st: d.timeStamp
  };
  if (d.requestBody) {
    if (d.requestBody.formData) {
      e.request.postData = { mimeType: "application/x-www-form-urlencoded", params: formDataToParams(d.requestBody.formData) };
    } else if (d.requestBody.raw && d.requestBody.raw.length) {
      try {
        const bytes = new Uint8Array(d.requestBody.raw[0].bytes);
        let s = "";
        for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
        e.request.postData = { mimeType: (d.requestBody.raw[0].contentType || ""), text: btoa(s) };
      } catch (err) {}
    }
  }
  har.inFlight[d.requestId] = e;
}, { urls: ["<all_urls>"] }, ["requestBody"]);

browser.webRequest.onBeforeSendHeaders.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  e.request.headers = hdrs(d.requestHeaders);
}, { urls: ["<all_urls>"] }, ["requestHeaders"]);

browser.webRequest.onHeadersReceived.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  e.response.status = d.statusCode;
  e.response.headers = hdrs(d.responseHeaders);
  const ct = (d.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type');
  if (ct) e.response.content.mimeType = ct.value;
}, { urls: ["<all_urls>"] }, ["responseHeaders"]);

browser.webRequest.onCompleted.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  delete har.inFlight[d.requestId];
  e.time = Math.max(0, d.timeStamp - e._st);
  e.timings.receive = e.time;
  const cl = (e.response.headers || []).find(h => h.name.toLowerCase() === 'content-length');
  if (cl) { const n = parseInt(cl.value, 10); if (!isNaN(n)) e.response.content.size = n; }
  delete e._st;
  har.entries.push(e);
}, { urls: ["<all_urls>"] });

browser.webRequest.onErrorOccurred.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  delete har.inFlight[d.requestId];
  e.response.status = 0;
  e.response.statusText = String(d.error || "Error");
  e.time = Math.max(0, d.timeStamp - e._st);
  delete e._st;
  har.entries.push(e);
}, { urls: ["<all_urls>"] });

/* --------------------- 弹窗 / 验证码 DOM 启发式上报 --------------------- */
browser.runtime.onMessage.addListener(msg => {
  if (msg && msg.event) post(msg);
});
"""

# content.js 降级版：仅验证码/弹窗扫描，无环境指纹 Hook
CONTENT_JS = r""";(function(){
  try {
    if (window.__geoEnvScan) return; window.__geoEnvScan = true;
    var RE = /(captcha|recaptcha|geetest|hcaptcha|verify|validate|antispick|safecaptcha)/i;
    var seen = {};
    function report(kind, title, message) {
      try {
        var key = kind + '|' + location.href;
        if (seen[key]) return; seen[key] = 1;
        browser.runtime.sendMessage({ event: 'popup', type: kind, title: title || '', message: message || '', url: location.href });
      } catch (e) {}
    }
    function scan() {
      try {
        var nodes = document.querySelectorAll('iframe,img[src],canvas,div[id],div[class]');
        var n = Math.min(nodes.length, 400);
        for (var i = 0; i < n; i++) {
          var el = nodes[i];
          var hay = (el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute && (el.getAttribute('src') || el.getAttribute('alt') || '')) || '';
          if (RE.test(hay)) { report('CAPTCHA', 'captcha-widget', String(hay).slice(0, 200)); break; }
        }
      } catch (e) {}
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function(){ scan(); setTimeout(scan, 1500); }, true);
    } else { scan(); setTimeout(scan, 1500); }
    var origOpen = window.open;
    window.open = function(u, n, f) {
      try { report('POPUP', String(n || '_blank'), String(u || '')); } catch (e) {}
      return origOpen.apply(window, arguments);
    };
  } catch (e) {}
})();
"""


def main() -> None:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", MANIFEST)
        zf.writestr("background.js", BACKGROUND_JS)
        zf.writestr("content.js", CONTENT_JS)
    size = os.path.getsize(OUT)
    print(f"生成 base.xpi -> {OUT} ({size} 字节)")
    with zipfile.ZipFile(OUT) as zf:
        for n in zf.namelist():
            print(f"  - {n} ({zf.getinfo(n).file_size} 字节)")


if __name__ == "__main__":
    main()
