# GeoEnv Marionette SDK 接入指南

> 面向自动化测试工程师 / SDK 开发者。
> GeoEnv 每个**环境（Environment）**可独立开启 Marionette 远程控制，端口互不相同，
> 协议为 Firefox 标准 **Marionette 协议**（TCP + JSON，兼容 `marionette_driver` /
> `selenium-webdriver` 的 Firefox 驱动）。

---

## 1. 概念与前提

| 项 | 说明 |
|---|---|
| 协议 | Firefox Marionette 协议（`[length, msgId, command, params]` 包装的 JSON） |
| 监听地址 | **仅 `127.0.0.1`（回环）**。Gecko 的 `nsIServerSocket` 以 `LoopbackOnly` 打开，内核层面不对外暴露；应用层再做端口占用/回环绑定校验 |
| 端口 | **每环境独立**。默认随机分配（20000–60000），也可自定义（1–65535） |
| 生效时机 | 端口/开关是 **Gecko 进程级** 配置，修改后需**重启该环境**生效 |
| 前置条件 | 环境必须处于 **RUNNING** 状态且 Marionette 开关为开 |
| Gecko 版本 | 锁定 `158.0.20261021001945`（arm64-v8a） |

### 1.1 查看连接端点

应用内路径：**环境详情 → 自动化（Marionette）** 页会列出所有运行中的端点，形如：

```
环境「账号A」  →  127.0.0.1:41273
环境「账号B」  →  127.0.0.1:38911
```

端口是动态的，**每次分配可能不同**，请以页面实时显示为准（也可用 `adb` 抓日志，见 §4.2）。

### 1.2 本机（手机上）连接

无需端口转发，直接连接：

```python
from marionette_driver.marionette import Marionette
client = Marionette('127.0.0.1', 41273)
client.start_session()
```

### 1.3 从 PC 连接（adb forward）

Marionette 只监听手机回环地址，PC 需要 adb 端口转发：

```bash
adb forward tcp:41273 tcp:41273
```

之后 PC 上连 `127.0.0.1:41273` 即可。应用内「自动化」页对每个端点都提供了
一键复制的 `adb forward` 命令。

> 多环境并发时，为每个端口各执行一次 forward：
> ```bash
> adb forward tcp:41273 tcp:41273
> adb forward tcp:38911 tcp:38911
> ```

---

## 2. Python 客户端（推荐）

### 2.1 安装

```bash
pip install marionette_driver
```

> `marionette_driver` 来自 Mozilla gecko-dev，随 Firefox / Marionette 一起维护，
> 版本与 Gecko 158 协议兼容。

### 2.2 最小示例：连接 + 导航 + 截图

```python
from marionette_driver.marionette import Marionette
from marionette_driver.marionette import By
import base64

HOST, PORT = '127.0.0.1', 41273   # 从应用「自动化」页获取

client = Marionette(HOST, PORT)
client.start_session()            # 握手，进入 session

# 导航
client.navigate('https://example.com')
print(client.get_url())           # 当前 URL
print(client.get_title())         # 标题

# 执行 JS
print(client.execute_script('return document.title;'))

# 截图（Marionette 截屏命令 → PNG 字节）
png = client.screenshot(full=False)          # 部分版本返回 base64 str
if isinstance(png, str):
    png = base64.b64decode(png)
open('shot.png', 'wb').write(png)

client.delete_session()
```

### 2.3 Cookie 操作（跨环境隔离验证）

```python
# 读取当前域名 Cookie
cookies = client.get_cookies()               # list[dict]
print(cookies)

# 写入 Cookie（用于注入登录态）
client.set_cookies({
    'name': 'sessionid',
    'value': 'abc123',
    'path': '/',
    'domain': 'example.com',
    'secure': True,
})

# 删除全部 Cookie
client.delete_cookies()
```

> **隔离性**：每个环境是独立 profile（`files/profiles/env_<id>`）+ 独立
> `contextId`（`geoenv.<envId>`），因此 A 环境注入的 Cookie 在 B 环境读不到。
> 这是验收标准「多环境 Cookie/LocalStorage 互不串号」的直接验证方式。

### 2.4 完整自动化脚本模板

```python
#!/usr/bin/env python3
"""GeoEnv 多环境并发自动化示例"""
from marionette_driver.marionette import Marionette
from concurrent.futures import ThreadPoolExecutor

ENDPOINTS = [
    ('账号A', '127.0.0.1', 41273),
    ('账号B', '127.0.0.1', 38911),
]

def run_one(name, host, port):
    c = Marionette(host, port)
    c.start_session()
    try:
        c.navigate('https://example.com/login')
        # 按环境不同注入不同身份
        c.execute_script(f'''
            document.querySelector('#username').value = "{name}";
            document.querySelector('#password').value = "secret";
            document.querySelector('form').submit();
            return true;
        ''')
        # 等待并断言
        c.set_context('chrome')          # 可切换到 chrome 上下文
        url = c.get_url()
        print(f'[{name}] -> {url}')
        return name, url
    finally:
        c.delete_session()

with ThreadPoolExecutor(max_workers=3) as pool:
    for r in pool.map(lambda e: run_one(*e), ENDPOINTS):
        print(r)
```

> 并发上限：GeoEnv 同时运行的环境数默认 3、最大 5（对应 5 个 Gecko 槽位进程）。
> 超过上限时新环境会排队等待空闲槽位，连接前请先在应用内确认目标环境为 RUNNING。

---

## 3. JavaScript / Node.js 客户端

Marionette 是纯 TCP JSON 协议，Node.js 可直接用 `net` 实现最小客户端。

### 3.1 协议帧格式

每条消息按 **Mozilla 的消息分帧** 收发：协议层为 JSON 数组
`[msgId, command, params]`，TCP 层使用长度前缀分帧（见下方实现，
已兼容 `marionette_driver` 同款 framing）。

### 3.2 最小实现（零依赖）

```js
// marionette-client.js — 零依赖 Marionette 客户端
const net = require('net');

class Marionette {
  constructor(host = '127.0.0.1', port) {
    this.host = host; this.port = port;
    this.msgId = 0;
    this.pending = new Map();
    this.buf = Buffer.alloc(0);
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.sock = net.connect(this.port, this.host, resolve);
      this.sock.on('error', reject);
      this.sock.on('data', d => this._onData(d));
    });
  }

  _onData(d) {
    this.buf = Buffer.concat([this.buf, d]);
    // 分帧：4 字节大端长度 + payload
    while (this.buf.length >= 4) {
      const len = this.buf.readUInt32BE(0);
      if (this.buf.length < 4 + len) break;
      const payload = this.buf.subarray(4, 4 + len).toString('utf8');
      this.buf = this.buf.subarray(4 + len);
      const [, id, , , error, result] = JSON.parse(payload);
      const cb = this.pending.get(id);
      if (cb) {
        this.pending.delete(id);
        error ? cb.reject(new Error(JSON.stringify(error))) : cb.resolve(result);
      }
    }
  }

  _send(command, params = []) {
    const id = ++this.msgId;
    const body = JSON.stringify([id, command, params]);
    const head = Buffer.alloc(4);
    head.writeUInt32BE(Buffer.byteLength(body), 0);
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.sock.write(Buffer.concat([head, Buffer.from(body)]));
    });
  }

  // ---- 高层 API ----
  async startSession()   { return this._send('WebDriver:NewSession'); }
  async navigate(url)    { return this._send('WebDriver:Navigate', [{ url }]); }
  async executeScript(script, args = []) {
    return this._send('WebDriver:ExecuteScript', [{ script, args }]);
  }
  async getTitle()       { return this._send('WebDriver:GetTitle'); }
  async getUrl()         { return this._send('WebDriver:GetCurrentURL'); }
  async screenshot()     { return this._send('WebDriver:TakeScreenshot'); } // base64
  async getCookies()     { return this._send('WebDriver:GetCookies'); }
  async setCookie(c)     { return this._send('WebDriver:AddCookie', [{ cookie: c }]); }
  async deleteSession()  { return this._send('WebDriver:DeleteSession'); }
}

module.exports = { Marionette };
```

### 3.3 使用示例

```js
const { Marionette } = require('./marionette-client');
const fs = require('fs');

(async () => {
  const c = new Marionette('127.0.0.1', 41273);   // 端口取自应用「自动化」页
  await c.connect();
  await c.startSession();

  await c.navigate('https://example.com');
  console.log(await c.getTitle());

  // 执行 JS
  const title = await c.executeScript('return document.title;');
  console.log(title);

  // 截图（base64 → PNG）
  const b64 = await c.screenshot();
  fs.writeFileSync('shot.png', Buffer.from(b64, 'base64'));

  // Cookie
  console.log(await c.getCookies());

  await c.deleteSession();
})().catch(e => { console.error(e); process.exit(1); });
```

### 3.4 Selenium WebDriver（Node）

若使用 `selenium-webdriver`，Marionette 端口等价于 Firefox 的 `port` 选项：

```js
const { Builder } = require('selenium-webdriver');
const firefox = require('selenium-webdriver/firefox');

const options = new firefox.Options();
options.setPreference('marionette', true);   // GeoEnv 侧已开启
// 通过已监听的端口连接（selenium 会复用该端口）
options.setPreference('webdriver.gecko.driver.port', 41273);

const driver = new Builder()
  .forBrowser('firefox')
  .setFirefoxOptions(options)
  .build();
```

> 更通用的做法仍是 §3.2 的原生协议客户端，或 Python 的 `marionette_driver`。

---

## 4. 调试与排错

### 4.1 连不上？

1. 环境是否 **RUNNING**？（Marionette 只在 runtime 创建时写入端口）
2. 开关是否打开、**改完是否重启了环境**？（端口是进程级配置）
3. 端口是否正确？（动态端口每次分配可能变化，以应用页显示为准）
4. PC 侧是否执行了 `adb forward tcp:<port> tcp:<port>`？
5. 是否有其它进程占用端口？（应用内会做占用校验并拒绝保存冲突端口）

### 4.2 抓取端口日志

```bash
adb logcat -s GeckoConsole Gecko  | grep -i marionette
```

应用侧状态也可通过审计日志查看：**审计** 页筛选操作类型 `MARIONETTE`，
会记录端口变更事件（含旧值/新值与时间戳）。

### 4.3 协议层抓包（验证只监听回环）

在手机上（需 root / adb shell）：

```bash
adb shell cat /proc/net/tcp | awk '{print $2}' | grep -i ':A1'   # 示例
# 更直观：
adb shell netstat -ltnp 2>/dev/null | grep <port>
```

期望看到 `127.0.0.1:<port>` 而非 `0.0.0.0:<port>`。

---

## 5. 安全须知

- **仅回环**：Marionette 端口只绑定 `127.0.0.1`，外部设备无法直连；
  必须经 `adb forward` 才能从 PC 访问（等价于物理接触设备）。
- **每环境独立端口**：不同环境的自动化会话互不干扰，也避免端口抢占。
- **审计留痕**：端口分配/变更写入审计日志（只追加，禁改删）。
- **建议**：不需要自动化时关闭该开关并重启环境，端口即释放。
- **不要**把 `adb forward` 出来的端口再经局域网共享给他人——
  Marionette 拥有对页面的完全控制权（执行 JS / 读写 Cookie）。

---

## 6. 快速对照表

| 动作 | Python (`marionette_driver`) | JS（§3.2 客户端） |
|---|---|---|
| 连接 | `Marionette(host, port)` + `start_session()` | `connect()` + `startSession()` |
| 导航 | `client.navigate(url)` | `c.navigate(url)` |
| 取 URL | `client.get_url()` | `c.getUrl()` |
| 执行 JS | `client.execute_script('return 1;')` | `c.executeScript('return 1;')` |
| 截图 | `client.screenshot(full=False)` | `c.screenshot()` → base64 |
| 读 Cookie | `client.get_cookies()` | `c.getCookies()` |
| 写 Cookie | `client.set_cookies({...})` | `c.setCookie({...})` |
| 关闭 | `client.delete_session()` | `c.deleteSession()` |
| PC 连接 | `adb forward tcp:P tcp:P` | `adb forward tcp:P tcp:P` |
