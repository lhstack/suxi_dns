# 速析 DNS 项目约定

## 项目概述

这是一个 Android DNS VPN 应用（项目名 `suxi_dns`，包名 `com.lhstack.suxi.dns`，展示名「速析 DNS」）。用户可以配置多个上游 DNS 端点，支持 UDP、HTTP、HTTPS 和 HTTP/3。启动后由 Android `VpnService` 接收发往虚拟 DNS 地址的 IPv4 UDP/53 请求，并发查询启用的上游端点，返回第一个通过 DNS 报文校验的响应。

当前实现边界：只处理 IPv4 UDP DNS 请求；IPv6、TCP/53、应用级 DNS over TLS/HTTPS 流量尚未接入。上游端点的 `host` 需要能在当前网络中解析；如果 VPN 已启动且网络环境无法提供该解析，应配置 IP 地址，或后续增加明确的 bootstrap 地址配置，不能静默回退到系统 DNS。

## 工程环境与主要工具

- Kotlin 2.4.10
- AGP 8.12.0
- Gradle 9.1.0
- Compose Multiplatform 1.11.1
- Android compileSdk/targetSdk 36，minSdk 24
- Android 端使用 OkHttp（HTTP/HTTPS）、Cronet（HTTP/3）
- Kotlin Multiplatform 共享模块仍包含 Android + iOS 示例代码；本 DNS VPN 功能是 Android 专属

## 目录与模块结构

- `androidApp`: Android 入口、Compose 配置页面、VPN Service、IPv4/UDP DNS 报文处理和上游传输实现
- `shared`: KMP 共享 Compose 入口以及跨平台的 DNS 协议和服务器配置模型
- `gradle/libs.versions.toml`: 版本和依赖目录

主要 Android 文件：

- `MainActivity.kt`: VPN 权限 Activity Result 流程
- `ui/DnsConfigScreen.kt`: 上游服务器编辑和启动/停止操作
- `ui/DnsConfigViewModel.kt`: 配置校验、序列化和服务控制
- `dns/DnsVpnService.kt`: TUN 生命周期、前台通知和 DNS 请求处理
- `dns/Ipv4UdpDnsPacket.kt`: IPv4/UDP/DNS 报文解析、响应封装和 SERVFAIL
- `dns/resolver/CompositeResolver.kt`: 首个成功上游竞速并取消其他请求
- `dns/resolver/UdpDnsResolver.kt`: UDP DNS
- `dns/resolver/HttpDnsResolver.kt`: HTTP/HTTPS `application/dns-message`
- `dns/resolver/Doh3Resolver.kt`: Cronet QUIC + HTTP/3，要求协商协议确实为 h3，不降级

## 分层和依赖方向

- UI/ViewModel → Android DNS/VPN 服务
- DNS/VPN 服务 → resolver 传输实现
- resolver 只依赖配置模型和外部网络库
- 上游网络 socket 必须在 VPN Service 中调用 `protect()`；Cronet 流量不被虚拟 DNS 路由捕获
- TUN 只路由虚拟 DNS 地址 `10.10.10.2/32`，不接管全量普通 IP 流量

## 业务流程与契约

1. 用户至少启用一个完整且合法的上游配置。
2. 点击启动时先调用 `VpnService.prepare`；未授权时必须启动系统授权 Activity。
3. 授权成功后启动前台 `DnsVpnService`。
4. VPN 仅接收发往虚拟 DNS 地址的 IPv4 UDP/53。
5. 每个请求并发发送到所有启用上游；第一个通过事务 ID和响应标志校验的 DNS 响应立即返回。
6. 所有上游失败时返回对应请求的 SERVFAIL，不伪造成功数据。
7. 停止、撤销权限或启动失败时关闭 TUN、取消协程并释放网络资源。

## 错误处理约定

- 配置错误在 ViewModel/Service 边界显式报错。
- 单个上游失败只作为竞速失败，不阻止其他上游。
- 所有上游失败必须保留失败原因并返回 SERVFAIL；不得返回空响应、默认解析结果或静默降级。
- HTTP/3 必须验证 Cronet 协商结果为 h3/quic；不能把 HTTP/2 当作 HTTP/3。

## 构建、测试和验证

- Android 编译：`./gradlew :androidApp:compileDebugKotlin`
- Android APK：`./gradlew :androidApp:assembleDebug`
- 共享模块 Android 测试：`./gradlew :shared:testAndroidHostTest`
- 完整 KMP/iOS 构建需要本机安装并配置 Xcode；没有 Xcode 时 iOS 链接失败不代表 Android 构建失败
- 当前已验证：Android `compileDebugKotlin` 和 `assembleDebug` 成功；真实设备上的 VPN、各上游协议和 IPv4 报文行为仍需设备/网络集成测试

## 编码约定

- Kotlin 官方格式，配置和传输对象使用不可变 `data class`。
- 网络 I/O 放在 `Dispatchers.IO` 或网络库回调中。
- 方法按高层流程组织，协议细节下沉到对应 resolver/报文类。
- 不添加未确认业务需求的缓存、fallback、协议别名或兼容分支。

## UI 结构

- 左上角菜单在「首页」与「DNS 服务器」之间切换。
- 首页：VPN 启停与进程内内存日志（解析 / VPN / 异常），进程退出后丢弃，不落盘。
- DNS 服务器：多上游列表，可分别启用/禁用、添加、删除；VPN 运行中只读。
- 上游配置持久化到应用私有目录 `dns_servers.json`；启动时加载到内存，增删改立即写回。
- 多上游并行竞速：取第一个通过校验的成功响应，其余取消。
- DoQ/QUIC 已移除；历史配置中的 QUIC 项在加载时会被剔除。

## 当前未确认事项

- iOS 是否需要同等 VPN/DNS 能力；当前没有实现。
- 是否需要 IPv6、TCP DNS、持久化配置、DNS 缓存、DNSSEC 校验、证书 pinning。
- 上游 hostname 的 bootstrap 解析地址配置。
- 当前目录没有可用于确认的版本控制元数据和历史提交记录。
