# GeoEnv 技术设计文档
应用名称：GeoEnv
仓库名称：geo-env-android
文档版本：V1.0
内核选型：GeckoView（Mozilla Gecko）
项目说明：独立安卓项目，与PC FinEnv无代码依赖，仅参考指纹环境产品思路。

## 1 整体架构
四层架构：
```
【Compose UI层】 ← 本地Kotlin接口通信 → 【业务管理层】
↓
【GeckoView实例管理器 + Marionette控制器】
↓
【多GeckoView独立实例（各自独立Profile）】
```
1. UI层：Jetpack Compose实现管理面板，环境列表、指纹表单、代理、日志、主题设置
2. 业务管理层：环境CRUD、账号台账、快照管理、加密、日志、权限校验
3. Gecko管理器：维护多个GeckoView实例生命周期、独立profile目录分配、Marionette启停、指纹注入
4. Gecko实例层：每个实例独立profile，独立Marionette端口，独立代理配置

> 核心约束：全部GeckoView实例运行在同一个App进程内，是实例级隔离，不是操作系统进程隔离；受安卓系统内存管控，并发建议上限5。

## 2 技术栈明细
### 2.1 主应用框架
- 语言：Kotlin
- UI：Jetpack Compose
- 最低SDK：API 26（Android 8.0）
- 架构模式：MVVM + Flow + StateFlow
- 依赖注入：Hilt
- 异步：Kotlin Coroutines

### 2.2 浏览器内核（核心）
- 内核：Mozilla GeckoView，锁定固定版本，不跟随系统浏览器
- ABI：优先 arm64-v8a；可选 x86_64（模拟器）
- 隔离方案：每个GeckoRuntime/GeckoSession分配独立profile目录
- 控制协议：Marionette（替代CDP，Firefox官方自动化协议）
- 指纹篡改方案：
  1. GeckoView配置参数层修改UA、设备、时区等基础指纹
  2. Gecko页面加载前注入JS Hook，修改Canvas/WebGL/Audio指纹
- 代理：Gecko内置代理配置，每个Session独立代理

### 2.3 本地存储 & 加密
- 数据库：Room（SQLite封装）
- 加密：SQLCipher 加密数据库；AES256-GCM 加密导出包
- 文件存储：App私有目录，每个环境独立profile文件夹；不使用外部公共存储
- 备份：打包加密zip环境包

### 2.4 自动化服务
- Marionette服务：每个激活的GeckoSession启动本地Marionette服务，仅监听127.0.0.1随机端口
- 对外SDK：提供Python Marionette示例脚本，方便自动化测试接入
- 不开放公网访问，仅本机应用内/本机adb调试使用

### 2.5 网络与辅助能力
- 代理连通检测：OkHttp
- HAR抓包：Gecko网络监听回调，收集请求生成har文件
- 日志：Timber，结构化日志，同时写入数据库审计表 + 文件日志
- 打包：Gradle，内置GeckoView aar依赖

### 2.6 主题系统
CSS/Compose ColorToken 色彩体系，亮色、暗色、跟随系统，支持自定义主色。

## 3 仓库目录结构
```
geo-env-android/
├── app/
│   └── src/main/
│       ├── java/com/geoenv/
│       │   ├── ui/                     # Compose UI页面、组件
│       │   │   ├── pages/              # 环境列表、指纹配置、代理、审计、设置、主题
│       │   │   ├── components/
│       │   │   └── theme/              # Compose主题、色彩token
│       │   ├── viewmodel/              # MVVM ViewModel
│       │   ├── manager/
│       │   │   ├── GeckoSessionManager.kt # Gecko实例生命周期管理
│       │   │   ├── MarionetteManager.kt  # Marionette端口、服务控制
│       │   │   ├── EnvManager.kt         # 环境业务管理
│       │   │   └── ProxyManager.kt
│       │   ├── db/                     # Room数据库、实体、迁移、SQLCipher封装
│       │   ├── crypto/                 # AES加密工具、密钥派生
│       │   ├── audit/                  # 审计日志
│       │   └── util/
│       └── res/
├── docs/
│   ├── geo-env-requirement.md
│   ├── geo-env-tech.md
│   └── marionette-sdk-guide.md
├── scripts/                            # 打包、签名、导出加密包脚本
└── README.md
```

## 4 主题自定义详细设计
基于Compose Material3，使用颜色Token管理，配置持久化存入Room数据库theme_config表
### 4.1 模式枚举
```kotlin
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}
```
4.2 色彩Token定义
 
- primary：主色（用户可自定义）
​
- primaryContainer、onPrimaryContainer
​
- surface、surfaceVariant
​
- onSurface、onSurfaceVariant
​
- outline
​
- error、success、warning
​
- 支持用户取色器修改primary色值，自动衍生配套浅色/深色变体
 
4.3 可配置项
 
1. 主题模式
​
2. 自定义主色HEX
​
3. 布局密度：Compact / Default
​
4. 导出theme.json、导入恢复主题
 
说明：主题仅作用GeoEnv管理面板UI，GeckoView内网页渲染不受该套颜色控制，不会影响浏览器指纹。
 
5 核心数据表（Room）
 
1. Environment 环境表：id、name、groupId、profilePath、proxyId、fingerprintSnapshotId、status、createTime
​
2. Account 账号表：envId、username、passwordEnc、remark、owner、status
​
3. FingerprintSnapshot 指纹快照表：envId、fingerprintJson、createTime
​
4. Proxy 代理池表
​
5. AuditLog 审计日志（仅追加，禁止删除）
​
6. ThemeConfig 主题配置表
所有敏感字段：passwordEnc、私有配置全部加密存储。
 
6 GeckoView关键实现要点
 
1. 一个GeckoRuntime可以复用，多个GeckoSession对应独立profile
​
2. Profile目录路径： context.filesDir.resolve("profiles/env_${envId}") ，每个环境独立目录
​
3. Marionette开启参数，每个Session分配随机可用本地端口
​
4. 页面onLoad前注入JS指纹Hook脚本
​
5. 内存监听：监控App内存，达到阈值提示用户关闭闲置环境，防止OOM被杀
 
7 打包、ABI与分发
 
- 产物：APK / AAB，内部企业分发，不上架商店
​
- ABI：优先arm64-v8a；模拟器附带x86_64
​
- GeckoView版本锁定，gradle固定版本号，不自动升级
​
- 支持离线绿色部署，不需要额外依赖
 
8 风险清单 & 应对方案
 
表格   
风险 应对方案 降级方案 
GeckoView包体积大 只打包需要的ABI，拆分产物；告知用户包体积说明 提供精简版本，去掉不必要Gecko组件 
内存高，多实例容易OOM 强制并发上限，超上限禁止新建；闲置自动回收；内存告警 上限默认3，用户可手动放开到5 
Marionette接口随Gecko版本变更 锁定Gecko版本，升级前完整回归自动化用例 固定版本不随便升级内核 
部分安卓定制ROM后台杀App 增加白名单引导提示，引导关闭电池优化 后台被杀后自动保存环境配置，下次打开恢复 
Gecko国内资料少、排错慢 保存官方文档、示例，封装日志输出Gecko原生异常 先单实例跑通，再做多实例 
 
9 开发规范
 
1. 所有GeckoSession生命周期统一由GeckoSessionManager管控，禁止零散创建销毁
​
2. 所有敏感操作、环境变更必须写入审计日志
​
3. 数据库变更必须写迁移脚本
​
4. Marionette仅监听127.0.0.1，禁止开放外网
​
5. 指纹Hook脚本版本跟随Gecko内核版本管理