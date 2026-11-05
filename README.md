# BanToNexus

LuminolCraft 的封禁同步插件：把 **Velocity 代理 + LibertyBans** 上的封禁状态与
**Nexus 后端**双向同步。后端是唯一权威状态（canonical），代理侧是副本 + 变更客户端。

- 插件 id：`bantonexus`，版本 `0.1`，作者 NatJerry
- 平台：Velocity
- 相关项目：`SkinToNexus`（皮肤同步）、`Luminol-Nexus`（后端）

---

## 功能

| 方向 | 内容 |
|---|---|
| 上行（代理 → 后端） | 监听 LibertyBans 的 `PostPunishEvent` / `PostPardonEvent`，把封禁与解封上报后端 |
| 下行（后端 → 代理） | 通过 WebSocket 实时接收 `ban.create` / `ban.update` / `ban.revoke`，在本地 LibertyBans 落地 |
| 对账 | 启动与重连时拉取全量快照 `GET /ban/sync`，按全局 `stateVersion` 与行级 `revision` 判定是否落后 |
| 重试 | 上报失败的操作进入内存队列，按 `retry-interval-sec` 周期批量冲刷 |

只同步 `minecraft` 类型的封禁；`website` / `chat` 不进同步通道。

---

## 运行环境

| 项 | 要求 |
|---|---|
| Velocity | 4.x（实测 `4.2.1-SNAPSHOT`） |
| Java | 21（`jvmToolchain(21)`） |
| LibertyBans | **必需**。插件声明了软依赖 `libertybans`，但初始化时 `orElseThrow()`，缺失会直接失败 |
| Nexus 后端 | 需提供 `/api/v1/internal/ban/*` 与 `/api/v1/internal/ban/ws` |

---

## 安装

1. 构建（见 [构建](#构建)），得到 `build/libs/BanToNexus-0.1-all.jar`。
2. **只部署 `-all.jar`**。同目录下的 `BanToNexus-0.1.jar` 是不含依赖的普通 jar，放进去会 `NoClassDefFoundError`。
3. 丢进服务器的 `plugins/`，启动一次，会生成 `plugins/BanToNexus/config.yml`。
4. 填入后端地址与密钥（见 [配置](#配置)）。
5. 重启代理生效。

---

## 配置

`plugins/BanToNexus/config.yml` 首次启动自动生成。

| 键 | 默认值 | 说明 |
|---|---|---|
| `api-base-url` | `http://localhost:8080` | 后端 HTTP 地址。只填 origin，不要带路径或结尾斜杠 |
| `ws-url` | `ws://localhost:8080` | WebSocket 地址。插件自行拼接 `/api/v1/internal/ban/ws` |
| `secret` | `some-fucking-secret` | 占位符，**必须替换**。值等于后端环境变量 `WEBHOOK_SECRET` |
| `connection-timeout-sec` | `5` | HTTP 连接超时 |
| `request-timeout-sec` | `10` | 单次 HTTP 请求超时 |
| `retry-interval-sec` | `30` | 失败队列轮询间隔 |
| `pending-flush-batch-size` | `20` | 单次批量上报条数。后端上限 20，不要调大 |
| `ws-ping-interval-sec` | `30` | WS 心跳间隔。服务端对文本 `ping` 自动回 `pong` |
| `reconnect-delay-sec` | `60` | 断线重连间隔 |

三点容易踩的：

1. **`ws-url` 必须直连 Worker 自定义域**（如 `wss://luminolcraft-nexus.narcssu.top`）。
   经反向代理（Netlify 等）会剥掉 `Upgrade` / `Connection` 头，握手永远建不起来。
   HTTP 与 WS 可以是两个不同的 host。
2. **生成的文件是 flow 风格、没有注释**。night-config 的 YAML writer 不输出注释，
   整份配置会写成一行 `{k: v, ...}`。功能正常，能读能改，只是不美观。
3. 文件为空（0 字节或只有注释）时插件会跳过解析、直接用默认值并写回，不会崩。

---

## 命令与权限

> **当前版本命令未注册。** `CommandManager` 已实现 `sync` / `reload` 两个子命令与权限节点
> `bantonexus.usage`，但 `Main.onProxyInitialization` 里没有调用
> `server.commandManager.register(...)`，所以游戏内敲不出来。

要启用需在初始化里补注册，例如：

```kotlin
server.commandManager.register(
    server.commandManager.metaBuilder("bantonexus").aliases("bnt").plugin(this).build(),
    CommandManager()
)
```

注册后行为：

| 命令 | 作用 |
|---|---|
| `/bnt sync` | 立即拉一次全量快照对账 |
| `/bnt reload` | 重载配置，重建 HTTP client 并重连 WS |

---

## 工作原理

```text
                 HTTP  POST /ban/add | /ban/revoke | /ban/sync(批量)
   LibertyBans  ───────────────────────────────────────────────▶  Nexus 后端
   事件 / 重试队列                                                     │ D1 commit
        ▲                                                             ▼
        │                                                    BanHub DO 广播
        └────────────── WebSocket ban.create/update/revoke ◀──────────┘
```

- **上行**：LibertyBans 事件 → `Main.postAddBan` / `postRevokeBan`；失败入 `retryPackageMap`，
  由定时任务批量走 `/ban/sync`（1–20 条/次）与 `/ban/revoke`。
- **下行**：`SocketManager` 收到帧后直接操作 LibertyBans（`enactPunishment` / `modifyPunishment` /
  `revokeByTypeAndVictim`）。收到 `sync.ack.resync = true` 或帧版本不连续时立即重拉快照。
- **对账**：`getFullBans()` 对比本地 active 封禁与快照，能改则改、缺则补；
  已存在的条目不会重复 enact（剔除依靠按 uuid 比对）。

---

## 与后端的契约

所有 `/api/v1/internal/*` 请求都带共享头 `X-Webhook-Secret`；缺失或错误一律 403。

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/api/v1/internal/ban/add` | 单条上报封禁 |
| `POST` | `/api/v1/internal/ban/revoke` | 上报解封（后端按 `playerName` + `createdAt` 定位） |
| `POST` | `/api/v1/internal/ban/sync` | 批量上报封禁，1–20 条 |
| `GET` | `/api/v1/internal/ban/sync` | 全量快照 `{ bans, stateVersion }` |
| `GET` | `/api/v1/internal/ban/ws` | WebSocket 升级 |

- 上行携带 `X-Ban-State-Version`（插件已 apply 的版本）；下行/响应带同名头与新版本。
- 响应体 `data.resync` 为真表示插件落后，应立即拉快照。
- 未绑定官网的玩家：单条 `/ban/add` 返回 404，批量 `/ban/sync` 静默跳过该条。

---

## 构建

仓库只提交了 `gradle/wrapper/gradle-wrapper.properties`，**没有 `gradlew` 脚本**。
用本机 Gradle 9.4.0（与 wrapper 声明一致）+ JDK 21：

```bash
gradle shadowJar --offline
```

产物：`build/libs/BanToNexus-0.1-all.jar`（约 8.3 MB，含 Kotlin 运行时、Ktor、night-config）。

打包时会把 `com.electronwill.nightconfig` 与 `org.yaml.snakeyaml` 重定位到
`org.luminolcraft.bantonexus.lib.*`，避免与 Velocity `server.jar` 自带的
`night-config-core` 在父加载器上冲突。

---

## 已知限制

按影响排序，均为当前实现的实际行为：

1. **重试队列与 `stateVersion` 只存在内存**。重启后未上报成功的封禁会丢，只能靠下一次快照对账收敛。
2. **不回撤**：对账只做「远端有 → 本地改/补」，不会把「远端已撤销但本地仍 active」的封禁解掉。
3. **回环无防护**：下行落地会触发 LibertyBans 事件，进而被 `EventManager` 再上报一次。
   后端靠唯一索引与 404 兜底，但会产生额外请求。
4. **WS 版本判据是 `!=`**：真实重放或批量帧（一次请求只 `+1`，多帧共享版本）都会触发一次全量快照。
5. **配置无注释**，且保存时会被重写成 flow 风格（见 [配置](#配置) 第 2 点）。
6. **命令未注册**（见 [命令与权限](#命令与权限)）。

---

## 排查

| 现象 | 原因 | 处理 |
|---|---|---|
| `NoFormatFoundException: No suitable format for config.yml` | Velocity `server.jar` 自带 night-config-core，父加载器优先，插件自带的 yaml 模块探测不到 | 已通过「显式传 `YamlFormat`」修复；升级到当前版本即可 |
| `ParsingException` + `Cannot invoke "Map.entrySet()" because "map" is null` | 空 YAML 文档，snakeyaml 返回 null | 已有空文档判空；不要再手工清空成一个空文件 |
| `Serializer for class 'DefaultClientWebSocketSession' is not found` | `ContentNegotiation` 把 WS 升级请求当成 JSON body | 已移除 `ContentNegotiation`；不要给这个 client 再加回来 |
| WS 握手一直失败 / 只能连上又立刻断 | `ws-url` 走了反向代理 | 改成直连 Worker 自定义域的 `wss://` |
| 所有内部请求 403 | `secret` 与后端 `WEBHOOK_SECRET` 不一致 | 两边改成同一个值 |

日志前缀统一为 `[bantonexus]`。
