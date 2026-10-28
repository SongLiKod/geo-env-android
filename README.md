# GeoEnv — 多环境指纹隔离浏览器（Android / GeckoView 158）

GeoEnv 是一个基于 **Mozilla GeckoView 158** 的 Android 指纹隔离环境浏览器：
在一台设备上并行运行多个互相隔离的浏览环境，每个环境拥有**独立的 Cookie /
LocalStorage / 缓存**、**独立可配置的浏览器指纹**、**独立的代理与 Hosts**，
并对外开放 **Marionette 自动化协议**，供外部脚本驱动页面、截图、读写 Cookie。

- 需求文档：`docs/geo-env-requirement.md`
- 技术方案：`docs/geo-env-tech.md`
- Marionette SDK 接入：`docs/marionette-sdk-guide.md`

---

## 1. 架构总览

```
┌────────────────────────── 主进程 (com.geoenv.browser) ──────────────────────────┐
│  Compose 管理面板                                                            │
│  ├─ 环境列表/详情  账号台账(加密)  代理池  审计日志  设置/主题             │
│  ├─ 指纹配置/校验/检测报告   快照   Hosts   HAR   弹窗聚合   分组          │
│  └─ EnvManager / ProxyManager / MarionetteManager / BackupManager / Report   │
│                     │  Messenger IPC（JSON 命令 + cbId 回调）                │
│                     ▼                                                        │
│   EnvManager：环境生命周期 + 槽位分配 + 并发闸门（默认 3，上限 5）         │
│   SQLCipher 数据库（主密钥 Keystore 派生，密码字段级 AES-GCM）             │
└──────────────┬───────────────┬───────────────┬───────────────┬───────────────┘
               │               │               │               │
      ┌────────▼───┐   ┌───────▼────┐   ┌──────▼─────┐  ┌──────▼─────┐  ┌──────▼────┐
      │  :gecko1   │   │  :gecko2   │   │  :gecko3   │  │  :gecko4   │  │  :gecko5   │
      │ SlotHost   │   │ SlotHost   │   │ SlotHost   │  │ SlotHost   │  │ SlotHost   │
      │ +Activity  │   │ +Activity  │   │ +Activity  │  │ +Activity  │  │ +Activity  │
      │ GeckoRuntime│  │ GeckoRuntime│  │ GeckoRuntime│ │ GeckoRuntime│ │ GeckoRuntime│
      │ Session    │   │ Session    │   │ Session    │  │ Session    │  │ Session    │
      │ 指纹扩展   │   │ 指纹扩展   │   │ 指纹扩展   │  │ 指纹扩展   │  │ 指纹扩展   │
      │ Marionette │   │ Marionette │   │ Marionette │  │ Marionette │  │ Marionette │
      │ LocalProxy │   │ LocalProxy │   │ LocalProxy │  │ LocalProxy │  │ LocalProxy │
      └────────────┘   └────────────┘   └────────────┘  └────────────┘  └────────────┘
        env_1 profile    env_2 profile   env_3 profile   env_4 profile   env_5 profile
        端口 41273        端口 38911       …               …               …
```

每个槽位是一个**独立的 Android 进程**（`:gecko1` ~ `:gecko5`），进程内持有一个
`GeckoRuntime` + `GeckoSession` + 指纹 WebExtension +（可选）Marionette 端口与本地代理。
主进程通过 Messenger IPC 下发命令、接收状态与数据。

### 1.1 关键设计：为何用「多进程槽位」而不是文档所述的「单进程」

> **架构偏离说明（重要，详见技术方案对照）**
>
> `docs/geo-env-tech.md` 原方案设想在**单进程**内通过 GeckoView 承载多环境。
> 实际实现改为 **多进程槽位架构**（每个槽位一个独立进程），原因如下：
>
> 1. **GeckoRuntime 是进程级单例**。GeckoView 官方约束：一个进程内只能存在
>    一个 `GeckoRuntime` 实例；其配置（`GeckoRuntimeSettings`：profile 路径、
>    代理 prefs、`marionette.port`、`MOZ_MARIONETTE` 环境变量、TZ、语言、
>    DPI/屏幕覆盖等）在**创建时一次性生效**，运行期无法按环境切换。
>    若强行单进程多环境，所有环境将共享同一套进程级配置 —— 代理、Marionette
>    端口、时区、语言会互相串号，直接违反「多环境互不串号」的验收标准。
> 2. **指纹隔离需要进程边界**。时区/语言通过 `putenv` 注入、代理通过 Gecko
>    prefs 注入、Marionette 端口通过 pref 注入，这些都是进程级状态。只有
>    「一环境一进程」才能保证 A 环境的 `TZ=Asia/Shanghai` 不影响 B 环境的
>    `TZ=America/New_York`。
> 3. **崩溃隔离与内存回收**。单进程内任一环境触发 Gecko 崩溃会拖垮全部环境；
>    多进程下崩溃只影响单个槽位，且系统可在内存紧张时单独回收空闲槽位进程。
>
> **代价与缓解**：多进程带来更高的内存占用与更复杂的 IPC。缓解措施：
> - 并发闸门（默认同时运行 3 个环境，上限 5）控制内存峰值；
> - `MemoryMonitor` 内存告警时自动释放闲置槽位（保留 1 个前台）；
> - 5 个槽位进程常驻但空闲时挂起，不加载 Gecko（仅在 start 时创建 runtime）。
>
> 该偏离已在应用内 **设置页 → 架构说明** 卡片中向用户明示。

### 1.2 隔离的四层保障

| 层级 | 机制 | 说明 |
|---|---|---|
| 1 | **独立进程** | `:gecko1`~`:gecko5`，进程级 prefs / env 互不可见 |
| 2 | **独立 profile 目录** | `filesDir/profiles/env_<id>`，经 DebugConfig `--profile` 注入 |
| 3 | **独立 session context** | `GeckoSessionSettings.contextId("geoenv.<envId>")`，Cookie/存储按 context 隔离 |
| 4 | **启动期探测降级** | `ProfileManager.probeProfileEffective()` 校验 profile 是否真正生效，失败时告警并依赖第 3 层 |

---

## 2. 功能清单（对照需求）

| 需求 | 实现 |
|---|---|
| 环境管理 | 创建/复制/重命名/删除/分组；启动-停止；并发闸门（默认 3 / 上限 5） |
| 账号台账 | 每环境账号；密码**字段级 AES-GCM 加密**存 SQLCipher；数据库内无明文 |
| 指纹配置 | UA / 屏幕 / DPI / 语言 / 时区 / WebRTC / Canvas·WebGL 钩子等 |
| 指纹校验 | 内置检测页 + `ReportManager` 生成检测报告 |
| 代理管理 | HTTP / HTTPS / SOCKS5；连通性检测；代理池；按环境绑定 |
| Marionette | **每实例独立端口**，仅绑定 `127.0.0.1`；开关 + 自定义端口校验 |
| 存储加密 | SQLCipher（主密钥 Keystore 派生）+ 导出包 AES-256-GCM + 自动备份 |
| 审计日志 | 只追加（TRIGGER 禁改删）+ CSV 导出 |
| UI 主题 | 亮/暗/跟随系统 + 主色取色 + 密度；重启保留；导入导出 |
| Hosts 劫持 | **单环境**生效：槽位内 `LocalProxyServer`（127.0.0.1 随机端口）按域名→IP 改写 |
| HAR 抓包 | 扩展 webRequest 组装；分页回收（binder 1MB）；停止落盘 `filesDir/har/` |
| 弹窗聚合 | PromptDelegate 全量收录 + 自动放行；验证码经内容脚本启发式聚合到面板 |
| 非功能 | minSdk 26 / arm64-v8a / 并发默认 3 上限 5 / 内存告警 / Gecko 版本锁定 / 内部分发 |

---

## 3. 目录结构

```
geo-env-android/
├── app/src/main/java/com/geoenv/browser/
│   ├── data/            # Room + SQLCipher 实体、DAO、迁移
│   ├── db/              # AppDatabase / Entities / Daos / Migrations
│   ├── gecko/           # ProfileManager / FingerprintConfig / HookScript / ExtensionFactory
│   ├── slot/            # SlotEngine / SlotBridge（槽位进程内 Gecko 引擎）
│   ├── service/         # BaseSlotHostService + SlotHostService1..5 / KeepAlive / Boot / MemoryMonitor
│   ├── manager/         # EnvManager / ProxyManager / MarionetteManager / BackupManager / ReportManager
│   ├── proxy/           # LocalProxyServer（Hosts 劫持 + 上游代理认证）
│   ├── di/              # Hilt AppModule / ServiceLocator
│   └── ui/              # Compose 管理面板（14 页面 + 9 ViewModel）
├── app/src/main/assets/extensions/geoenv/   # 降级用静态占位扩展 base.xpi
├── repo/org/mozilla/geckoview/              # GeckoView 158 本地 Maven（锁定版本）
├── scripts/             # 打包 / 签名 / 导出环境包脚本 + release keystore
└── docs/                # 需求 / 技术方案 / Marionette SDK 指南
```

---

## 4. 构建

### 4.1 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | compileSdk 37（`android.suppressUnsupportedCompileSdk=37` 已配置） |
| Gradle | 8.13 |
| AGP | 8.13.1 |
| Kotlin | 2.4.0（`kotlin-stdlib` 降级到 2.3.21 以兼容 Hilt 2.58） |
| GeckoView | `158.0.20261021001945`（**arm64-v8a**，本地 `repo/` 锁定） |

### 4.2 一键构建

```bash
# 构建 release（自动用 scripts/geoenv-release.jks 签名）
scripts/build.sh

# 构建 debug
scripts/build.sh debug

# clean 后构建 release
scripts/build.sh clean
```

产物：

- Debug：`app/build/outputs/apk/debug/app-debug.apk`
- Release：`app/build/outputs/apk/release/app-release.apk`（已签名）

### 4.3 直接用 Gradle

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

gradle -p . :app:assembleDebug
gradle -p . :app:assembleRelease
```

### 4.4 离线 / 可重复构建

GeckoView 版本已通过本地 Maven 仓库 `repo/` 锁定：

```kotlin
// settings.gradle.kts
maven { url = uri("file://${rootDir}/repo") }
```

```kotlin
// app/build.gradle.kts
implementation("org.mozilla.geckoview:geckoview-arm64-v8a:158.0.20261021001945")
```

只要 `repo/` 目录存在，GeckoView 不会从公网拉取，保证内核版本与指纹 Hook 脚本
版本一一对应（需求 3.4「Gecko 版本锁定」）。

> 若本地仓库缺文件，构建会回退到 `https://maven.mozilla.org/maven2` 兜底。

---

## 5. 签名与分发

GeoEnv 采用**内部 APK / AAB 分发**（不上架应用商店）。

| 项 | 值 |
|---|---|
| Keystore | `scripts/geoenv-release.jks` |
| Alias | `geoenv` |
| 口令 | `geoenv2026`（可用项目根 `keystore.properties` 覆盖） |
| ABI | `arm64-v8a`（需求：arm64 优先；本次仅打包 arm64-v8a） |

`keystore.properties` 覆盖示例（放在项目根目录）：

```properties
storePassword=你的store口令
keyAlias=geoenv
keyPassword=你的key口令
```

### 校验 / 重签名

```bash
scripts/sign.sh verify                       # 校验 app-release.apk 签名
scripts/sign.sh info    path/to/app.apk      # 打印证书信息
scripts/sign.sh resign  path/to/app.apk out.apk   # 用 geoenv keystore 重签名
```

> release 构建启用 `minify` + `shrinkResources`，并已配置
> `proguard-rules.pro`（GeckoView / Room / Hilt / SQLCipher 保留规则）。

---

## 6. 导出环境包（`.geoenv`）

加密环境包用于跨设备迁移环境配置。格式：

```
"GEOPKG1\n"  │ salt(16) │ iv(12) │ AES-256-GCM(密文||tag)
密钥  = PBKDF2WithHmacSHA256(passphrase, salt, 210000) → 256-bit
AAD   = b"geoenv-pkg:" + salt
明文  = ZIP{ manifest.json, environments.json, accounts.json, snapshots.json,
             proxies.json, hosts.json, groups.json, theme.json [, audit.json]
             [, profiles/env_<id>/…] }
```

`scripts/export_env.py` 与应用内 `BackupManager` **完全同格式、双向兼容**
（已用 JDK `javax.crypto` 与 Python 双向交叉验证）。

```bash
# 查看包摘要
python3 scripts/export_env.py info  my.geoenv --pass <口令>

# 解包到目录（人工检查 / 修改）
python3 scripts/export_env.py unpack my.geoenv ./out --pass <口令>

# 从目录打包
python3 scripts/export_env.py pack ./out my2.geoenv --pass <新口令>

# 仅校验文件格式（不需口令）
python3 scripts/export_env.py check my.geoenv
```

- 零第三方依赖（PBKDF2 用 `hashlib`，AES-GCM 自带纯 Python 实现；
  若装有 `cryptography` / `pycryptodome` 会自动优先使用）。
- 口令至少 8 位；错口令 / 密文篡改均被 GCM 认证标签拒绝。
- 应用内自动备份的口令由设备 Keystore 派生（**跨设备不可解**），
  手动导出/导入须使用用户自设口令。

> **注意**：账号密码、代理密码等字段级密文由**本机 Keystore 派生密钥**加密，
> 跨设备导入后需在新设备上重新设置（导入时会清空并提示重填），这是
> Keystore 硬件绑定的预期行为。

---

## 7. Marionette 自动化

每个环境可独立开启 Marionette，端口互不相同，仅监听 `127.0.0.1`。

- 端口查看/连接方式：`docs/marionette-sdk-guide.md`
- 应用内路径：**环境详情 → 自动化（Marionette）**（含 Python/JS 示例与 `adb forward` 命令）
- 端口是 Gecko **进程级**配置，修改后需**重启该环境**生效
- 协议兼容 `marionette_driver` / Selenium Firefox 驱动

快速开始（PC 侧）：

```bash
adb forward tcp:41273 tcp:41273
python -c "
from marionette_driver.marionette import Marionette
c = Marionette('127.0.0.1', 41273); c.start_session()
c.navigate('https://example.com'); print(c.get_title())"
```

---

## 8. 保活 / 内存 / 开机恢复

- `KeepAliveService`：`specialUse` 前台服务 + `WakeLock`，由 `MemoryMonitor` 驱动。
- 内存告警（CRITICAL）时自动释放闲置槽位，保留 1 个前台环境。
- `BootReceiver`：仅当设置中开启 `keep_alive` 时开机拉起服务，并按**空闲槽位**
  恢复 `keepAlive=true` 且状态为 RUNNING 的环境（不硬编码槽位号）。
- 保活 / WakeLock 开关持久化在 `geoenv_settings` SharedPreferences。

---

## 9. 测试

```bash
gradle -p . :app:testDebugUnitTest
```

---

## 10. 需求验收标准对照

| 验收标准 | 达成方式 |
|---|---|
| 多环境 Cookie / LocalStorage 互不串号 | 独立进程 + 独立 profile + 独立 `contextId` |
| App 重启后配置与登录态保留 | Room(SQLCipher) 持久化 + profile 目录持久化 |
| Marionette 客户端可连接并截图 | 每环境独立端口，`docs/marionette-sdk-guide.md` 有截图示例 |
| 主题重启不丢失 | Theme 存数据库 + 启动时恢复 |
| 加密包跨设备可导入 | `BackupManager` / `export_env.py` 同格式，双向兼容 |
| 数据库内密码无明文 | SQLCipher 全库加密 + 密码字段级 AES-GCM（`v1:<iv>:<ct>`） |
