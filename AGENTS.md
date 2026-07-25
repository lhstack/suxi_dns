# 速析 DNS 项目约定

## 项目概述

这是一个 Android DNS VPN 应用（项目名 `suxi_dns`，包名 `com.lhstack.suxi.dns`，展示名「速析 DNS」，当前版本 `1.0.1`）。用户可以配置多个上游 DNS 端点，支持 UDP、HTTP、HTTPS 和 HTTP/3；支持自定义解析与域名拦截。上游按**分组**组织：组内启用上游并发竞速取最快，组间按列表顺序 fallback（当前组全部失败才尝试下一组）。启动后由 Android `VpnService` 接收发往虚拟 DNS 地址的 IPv4 UDP/53 请求，按「拦截 → 本地解析 → DNS 缓存 → 上游分组 fallback」处理查询。

当前实现边界：只处理 IPv4 UDP DNS 请求；IPv6、TCP/53 尚未接入。DNS 缓存为进程内、按 TTL 过期、不落盘。上游端点的 `host` 需要能在当前网络中解析；如果 VPN 已启动且网络环境无法提供该解析，应配置 IP 地址，或后续增加明确的 bootstrap 地址配置，不能静默回退到系统 DNS。

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
- `dns/resolver/CompositeResolver.kt`: 按分组 fallback，组内并发竞速取首个成功响应并取消其余请求
- `dns/DnsResponseCache.kt`: 进程内 DNS 响应缓存，按「域名 + 查询类型」缓存，按答案区最小 TTL 过期，不缓存 SERVFAIL
- `dns/DnsServerStore.kt`: 分组配置持久化，兼容旧扁平数组并自动迁移为单分组
- `dns/DnsServersInitializer.kt`: 首次启动写入默认两个分组（首选 UDP 并发 / 备用 HTTP/3 fallback）
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

1. 用户至少启用一个含有效上游的分组（启用的分组必须至少有一个启用且合法的上游）。
2. 点击启动时先调用 `VpnService.prepare`；未授权时必须启动系统授权 Activity。
3. 授权成功后启动前台 `DnsVpnService`。
4. VPN 仅接收发往虚拟 DNS 地址的 IPv4 UDP/53。
5. 每个请求先查拦截规则与本地解析，再查 DNS 缓存；未命中才走上游。
6. 上游按分组顺序 fallback：组内所有启用上游并发发送，第一个通过事务 ID 和响应标志校验的响应立即返回；当前组全部失败才尝试下一组。
7. 上游成功（NOERROR/NXDOMAIN）的响应按 TTL 写入缓存；所有分组失败时返回 SERVFAIL，不伪造成功数据。
8. 停止、撤销权限或启动失败时关闭 TUN、取消协程并释放网络资源。

## 错误处理约定

- 配置错误在 ViewModel/Service 边界显式报错。
- 单个上游失败只作为组内竞速失败，不阻止同组其他上游，也不阻止 fallback 到下一组。
- 所有分组全部失败必须保留失败原因并返回 SERVFAIL；不得返回空响应、默认解析结果或静默降级。
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
- 分组 fallback 与 DNS 缓存是已确认的业务需求；除此之外不添加未确认的降级、协议别名或兼容分支。

## UI 结构

- 左上角菜单：首页、DNS 服务器、自定义解析、域名拦截。
- 首页：VPN 启停与进程内内存日志（解析 / VPN / 异常），进程退出后丢弃，不落盘。
- DNS 服务器：分组列表。每个分组可命名、整体启停、组内增删改上游；组左侧手柄长按可拖拽调整组的 fallback 顺序；VPN 运行中只读。
- 自定义解析：本地 A/AAAA/CNAME 记录，支持 `*` 通配与 TTL；持久化 `local_dns_records.json`。
- 域名拦截：通配符或正则规则，命中返回 NXDOMAIN；持久化 `domain_block_rules.json`。
- 查询顺序：拦截 → 本地解析 → DNS 缓存 → 上游分组 fallback。
- 分组配置持久化到应用私有目录 `dns_servers.json`（数组）；启动时加载到内存，增删改立即写回；旧版扁平上游数组在加载时自动迁移为单个分组。
- 分组 fallback + 组内并发竞速：组内取第一个通过校验的成功响应其余取消，组全失败才 fallback 下一组。
- DoQ/QUIC 已移除；历史配置中的 QUIC 项在加载时会被剔除。
- VPN 停止时须对读包/写包协程做停止标志与异常吞没，避免关闭后写 TUN 导致闪退。

## 当前未确认事项

- iOS 是否需要同等 VPN/DNS 能力；当前没有实现。
- 是否需要 IPv6、TCP DNS、DNSSEC 校验、证书 pinning。（DNS 缓存已实现：进程内、按 TTL 过期、不落盘。）
- 上游 hostname 的 bootstrap 解析地址配置。
- 当前目录没有可用于确认的版本控制元数据和历史提交记录。
