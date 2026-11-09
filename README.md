# XPWeChatCall - 微信快捷通话

[![Android](https://img.shields.io/badge/Android-11+-blue)](https://www.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-supported-green)](https://modules.lsposed.org)
[![XposedModule](https://img.shields.io/badge/XposedModule-repo-green)](https://github.com/Xposed-Modules-Repo)
[![Build APK](https://github.com/cdtubear/XPoser_WeChatCall/actions/workflows/build-apk.yml/badge.svg)](https://github.com/cdtubear/XPoser_WeChatCall/actions/workflows/build-apk.yml)

通过 Xposed 模块，在桌面为好友创建独立图标，点击图标一步直达微信视频/语音通话

## 原理

模块为每个好友生成一个桌面快捷方式，快捷方式点击后由模块的透明入口 `ShortcutActivity` 接收，
再通过微信主界面 `LauncherUI` 把「好友 wxid + 通话类型」透传给微信进程；
注入到 `com.tencent.mm` 的 Hook 拦截 `LauncherUI` 的 `onCreate` / `onNewIntent`，
读取参数后打开该好友聊天页，并按界面文案模拟点击完成通话

```text
桌面好友图标
  -> ShortcutActivity（模块进程，读取好友配置）
  -> 拉起 com.tencent.mm/.ui.LauncherUI，附带 wxid 与通话参数
  -> WeChatHook 拦截 LauncherUI onCreate/onNewIntent
  -> UiCallHelper 打开聊天页，依次点击「+」与「视频通话」
  -> 微信弹出选择框，按需选择视频/语音通话
```

Hook 边界选择稳定且可被外部拉起的 `com.tencent.mm.ui.LauncherUI`，不依赖混淆的业务函数；
发起通话采用模拟点击，只依赖界面文案，跨版本适应性更好

## 功能

- 在模块内维护好友列表（备注、wxid、视频/语音）
- **从微信直接读取好友列表，无需手动填写 wxid**
- **导入为镜像导入：完全以微信好友为准，替换本地列表**
- **首页支持搜索好友，关键字同时匹配备注与 wxid**
- 为每个好友一键创建桌面图标，图标默认使用微信好友头像，也可在编辑弹窗中自选图片
- 点击桌面图标即对好友发起视频通话或语音通话
- **支持桌面控件（1×1 / 2×2 / 4×4），绑定好友后点击直接通话**
- **支持外部触发：模块内置 HTTP 服务，webhook 可按昵称或 wxid 发起通话并查询结果**
- **支持蓝牙触发：手机作为 BLE 外设，外部设备写入 JSON 指令发起通话并接收结果**
- **支持 root 看门狗保活：App 进程被杀后自动恢复 webhook / 蓝牙服务**
- **支持 root 锁屏唤醒 + 自动解锁（仅滑动锁）**
- 支持快捷方式固定（`ShortcutManager`）与旧版广播两种创建方式
- 支持写入运行日志，便于排查

## 图片预览

![001](imgs/001.jpg?20260711)

![002](imgs/002.jpg?20260711)

更多图片：[imgs](imgs/README.md)

## 环境要求

- Android 11+（minSdk 30）
- 已安装 Xposed 框架（LSPosed / EdXposed 等）
- 已安装微信
- 支持的 Xposed 作用域：`com.tencent.mm`

## 使用步骤

1. 安装并激活模块，在 LSPosed 中勾选作用域 `com.tencent.mm`，重启微信
2. 打开「微信快捷通话」App，点击右上角「添加好友」
3. 填写好友备注与好友 wxid，选择视频通话或语音通话，保存
4. 点击好友右侧「创建图标」，在桌面确认添加
5. 之后点击桌面图标即可直接对该好友发起通话

### 如何获取好友 wxid

点击主界面右上角「导入好友」即可自动读取微信好友列表，无需手动填写 wxid。

导入原理：微信使用 WCDB 访问 `EnMicroMsg.db`，好友数据存放在 `rcontact` 表。
模块挂钩 WCDB 的数据库构造函数捕获已打开的主库连接（内部已持有密钥），
再用其 `rawQuery` 查询 `rcontact`，按 `type` 位标志与用户名过滤出真实好友，
最后通过 `ContentProvider` 回传本应用。该方式不依赖解密数据库、无需 root。

使用步骤：

1. 确认模块已在 LSPosed 中对 `com.tencent.mm` 启用，并已重启微信
2. 打开本应用，点击「导入好友」
3. 应用会自动拉起微信触发读取，等待片刻后完成镜像导入

若仍需要手动获取 wxid（例如导入失败时），可通过以下方式：

- 使用微信数据库导出/查看类工具（如 WeChatMsg 等）从聊天记录中获取
- 若好友已设置微信号，部分工具会同时显示其 wxid

## 外部触发（Webhook / HTTP）

主界面右上角 **⚙ → 外部触发** 打开设置页。服务默认关闭，开启后常驻一个前台通知。

前提条件：

- 手机已 root（root 用于后台拉起微信、唤醒屏幕和解锁）
- 微信已登录，且至少成功导入过一次好友
- 锁屏自动解锁目前只支持**滑动锁**；设置了密码 / PIN / 图案锁会返回 `DEVICE_LOCKED`
- 监听地址默认 `0.0.0.0:8790`，建议改为 Tailscale / WireGuard 地址，避免暴露在公共网络

### 鉴权

所有接口都必须携带设置页中的 Token：

```http
Authorization: Bearer <TOKEN>
```

也接受 `X-Xp-Token: <TOKEN>`。Token 可在设置页复制或重置；重置后旧调用方立即失效。

### 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/health` | 服务、root、好友数量、微信安装状态、最近 Hook 回传时间 |
| GET | `/v1/friends` | 获取全部好友列表，支持分页与关键字过滤 |
| POST | `/v1/resolve` | 按昵称 / 备注查 wxid |
| POST | `/v1/call` | 发起视频或语音通话，立即返回 `202` |
| GET | `/v1/result/{request_id}` | 查询请求状态（回调之外的轮询方式） |

`/v1/friends` 请求示例：

```bash
# 获取全部好友
curl -H "Authorization: Bearer <TOKEN>" http://手机IP:8790/v1/friends

# 分页 + 关键字过滤
curl -H "Authorization: Bearer <TOKEN>" \
  "http://手机IP:8790/v1/friends?offset=0&limit=50&keyword=张三"
```

`/v1/friends` 查询参数：

| 参数 | 说明 |
| --- | --- |
| `offset` | 可选，起始下标，默认 `0` |
| `limit` | 可选，返回数量；不传或小于等于 `0` 时返回全部 |
| `keyword` / `q` | 可选，按 wxid、展示名、备注、昵称过滤，忽略大小写与全角半角 |

返回结构：

```json
{
  "ok": true,
  "data": {
    "total": 128,
    "count": 50,
    "offset": 0,
    "synced_at": 1762300000000,
    "stale": false,
    "friends": [
      { "wxid": "wxid_abc123", "display": "张三", "remark": "客户张三", "nickname": "三三" }
    ]
  }
}
```

`total` 是去重并应用 `keyword` 后的总数，`count` 是本次返回数量；`stale=true` 表示好友数据超过 7 天未更新，建议在 App 内重新导入。未同步过好友数据时返回 `ok=false`、`error=NOT_SYNCED`。

`/v1/resolve` 请求示例：

```bash
curl -X POST http://手机IP:8790/v1/resolve \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","fuzzy":true,"limit":20}'
```

`/v1/call` 请求示例：

```bash
curl -X POST http://手机IP:8790/v1/call \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"张三","type":"video"}'
```

`/v1/call` 支持字段：

| 字段 | 说明 |
| --- | --- |
| `wxid` / `name` | 二选一；同时提供时以 `wxid` 为准 |
| `type` | `video`（默认）或 `voice` |
| `request_id` | 可选，调用方自定义，便于幂等与查询；允许 `A-Z a-z 0-9 _ . : -`，最长 64 字符 |
| `callback_url` | 可选，结果回调地址，域名必须在设置页白名单中 |
| `timeout_sec` | 可选，5–120 秒，默认取设置页配置 |
| `pick_first` | 最高匹配分对应多个好友时，是否直接取第一个；默认 `false` 并返回 `FRIEND_AMBIGUOUS` |

最高匹配分对应多个好友时，响应会带上候选列表，调用方可改用 `wxid` 精确发起；如果只有一个精确匹配，即使存在其他模糊匹配也会自动选中。

### 状态

```text
ACCEPTED -> LAUNCHED -> CHAT_OPENED -> PANEL_OPENED -> CALL_MENU_OPENED -> SUCCESS
                                  \-> FAILED（带 error）
```

`SUCCESS` 只表示本机已点击到通话界面，**不代表对方接听**。

常见错误码：`UNAUTHORIZED`、`DISABLED`、`RATE_LIMITED`、`BUSY`、`FRIEND_NOT_FOUND`、`FRIEND_AMBIGUOUS`、`NOT_SYNCED`、`ROOT_DENIED`、`DEVICE_LOCKED`、`LAUNCH_BLOCKED`、`TIMEOUT`。

### 结果回调

在设置页填写允许回调的域名后，把 `callback_url` 传给 `/v1/call`。终态时模块会向该地址 POST 请求记录 JSON，并带上：

```http
X-Xp-Request-Id: <request_id>
X-Xp-Signature: sha256=<HMAC-SHA256(请求体, TOKEN)>
```

失败会自动重试 3 次；也可以随时轮询 `/v1/result/{request_id}`。

### OpenAPI / 接口调试

完整接口定义见 [`docs/openapi.yaml`](docs/openapi.yaml)（OpenAPI 3.0.3），包含鉴权方式、请求/响应结构、错误码、状态机、回调签名和示例。

可直接导入 Apifox / Postman / Swagger UI：

- Apifox：项目设置 → 导入数据 → OpenAPI/Swagger → 选择 `docs/openapi.yaml`
- Postman：Import → 选择 `docs/openapi.yaml`
- 本地预览：

```bash
npx @redocly/cli preview-docs docs/openapi.yaml
```

导入后把 Server 变量 `host`、`port` 改成手机实际地址，并在环境变量或 Auth 中填入设置页的 Token（`Authorization: Bearer <TOKEN>` 或 `X-Xp-Token`）。

### ContentProvider（Tasker / 其它 App）

Token 鉴权同样适用，可选配置调用方包名白名单：

```java
Bundle extras = new Bundle();
extras.putString("auth", "<TOKEN>");
Bundle result = getContentResolver().call(
    Uri.parse("content://com.zgcwkj.xpwechatcall.trigger"),
    "start_call", "{\"name\":\"张三\",\"type\":\"video\"}", extras);
String json = result.getString("result");
```

方法名：`resolve_friend`、`list_friends`、`start_call`、`query_result`、`health`。`list_friends` 的 `arg` 支持 `{"offset":0,"limit":50,"keyword":"张三"}`，字段含义与 `/v1/friends` 一致。桌面快捷方式不受该接口影响，也不需要 Token。

## 蓝牙触发（BLE）

除了 HTTP，模块还可以把手机当作 **BLE 外设（GATT Server）**。外部设备（ESP32、nRF52、Arduino、nRF Connect 等）连接后向 RX 特征写入 JSON 指令，即可发起通话，并通过 TX 特征 Notify 收到即时响应和最终结果。

蓝牙默认关闭，与 HTTP 相互独立：

- 只开 HTTP：行为与旧版本一致
- 只开蓝牙：服务仍会运行，但不监听 HTTP 端口
- 两者都开：共用同一个前台服务、同一套 Token、好友匹配、忙状态和结果存储

### 启用步骤

1. 打开 **⚙ → 外部触发**
2. 在「蓝牙触发（BLE）」区域打开 **启用蓝牙触发**
3. Android 12+ 授予「附近的设备」权限（`BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`）
4. 打开系统蓝牙；需要时在系统蓝牙设置中与设备配对
5. 建议保留「仅允许已配对设备」开启

### BLE 参数

| 项目 | 值 |
| --- | --- |
| Service UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX UUID（设备写入） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX UUID（设备订阅 Notify） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| 帧格式 | UTF-8 JSON，推荐以 `\n` 结尾，单条上限 1024 字节 |
| 鉴权 | 每条指令必须包含 `token` |

连接后必须先订阅 TX 的 Notify，否则收不到响应。

### 指令示例

```json
{"token":"<TOKEN>","action":"call","name":"张三","type":"video"}
```

支持的 `action`：`call`、`query`、`health`、`friends`、`resolve`、`ping`、`help`。

即时响应与 HTTP 接口结构一致：

```json
{"ok":true,"request_id":"bt_1762300000000_1a2b3c","state":"ACCEPTED","wxid":"wxid_abc123","type":"video"}
```

通话进入终态后会额外推送：

```json
{"event":"result","ok":true,"data":{"request_id":"bt_1762300000000_1a2b3c","state":"SUCCESS"}}
```

响应超过 MTU 时会自动分片，格式为 `@序号/总数:内容`，设备端按序号拼接即可。大响应建议在设备端协商 MTU 247。

完整协议、nRF Connect 步骤、ESP32 示例和排错说明见 [`docs/bluetooth.md`](docs/bluetooth.md)。

## 项目结构

```text
app/src/main/java/com/zgcwkj/
  comm/
    ConfigHelp.java       配置读写（应用私有目录 config.json）
    LogHelp.java          日志输出（logcat + 可选文件）
    Friend.java           好友数据模型
    FriendSync.java       好友数据回传通道常量与落盘
    ShortcutHelp.java     桌面快捷方式创建与图标生成
    TriggerContract.java  外部触发契约：方法名、状态、错误码、Token、配置
    FriendIndex.java      本地好友索引：按昵称 / 备注查 wxid、分页获取好友列表
    CallReporter.java     微信进程向模块回传通话步骤结果
  xpwechatcall/
    XposedEntry.java      Xposed 入口
    MainActivity.java     好友列表管理界面
    FriendAdapter.java    好友列表适配器
    FriendProvider.java   好友数据接收 Provider
    ShortcutActivity.java 快捷方式入口，向微信转发通话请求
    TriggerSettingsActivity.java 外部触发设置页
    hook/
      WeChatHook.java     拦截微信主界面，触发通话
      ContactHook.java    捕获 WCDB 主库连接，读取微信好友列表
    trigger/
      TriggerApi.java     外部触发核心：解析好友、发起、查询、回调
      TriggerProvider.java 外部触发 ContentProvider
      TriggerService.java 外部触发前台服务：HTTP 监听 + BLE 生命周期、心跳
      TriggerStore.java   请求状态持久化、幂等、超时
      BtProtocol.java     蓝牙协议：UUID、动作、Token 校验、JSON 工具
      BtCommandHandler.java 蓝牙指令分发到 TriggerApi
      BluetoothTriggerManager.java BLE GATT Server：广播、连接、分片、终态 Notify
      RootShell.java      root 拉起微信、唤醒解锁、执行 root 命令
      WatchdogHelper.java root 看门狗脚本安装、卸载、状态检测
      KeepAliveHelper.java 电池白名单、MIUI / HyperOS 自启动入口
      BootReceiver.java   开机 / 升级后恢复外部触发服务
    voip/
      UiCallHelper.java   打开聊天页并模拟点击发起通话
    widget/
      CallWidgetProvider.java 桌面控件基类，绑定好友并刷新显示
      Widget1x1Provider.java  1×1 尺寸控件
      Widget2x2Provider.java  2×2 尺寸控件
      Widget4x4Provider.java  4×4 尺寸控件
      WidgetConfigActivity.java 控件配置页（选择好友与通话类型）
app/src/main/res/
  layout/                 界面布局
  values/                 字符串、颜色、Xposed 作用域
  xml/                    桌面控件描述文件
```

## 实现说明

| 模块 | 作用 |
| --- | --- |
| `ShortcutHelp` | 生成带好友信息的桌面快捷方式，图标优先使用好友头像或自定义图片 |
| `ShortcutActivity` | 透明入口，读取好友配置后拉起微信主界面并透传参数 |
| `WeChatHook` | 拦截 `LauncherUI` 生命周期，识别通话参数并清理标记 |
| `ContactHook` | 捕获 WCDB 主库连接，从 `rcontact` 表读取并过滤好友列表 |
| `UiCallHelper` | 打开好友聊天页，按界面文案模拟点击「+」与通话按钮 |
| `CallWidgetProvider` | 桌面控件，显示好友头像与名字，点击直接发起通话 |
| `TriggerService` | 外部触发前台服务：HTTP 监听、BLE 生命周期、Token 鉴权、限流、超时扫描、心跳 |
| `TriggerApi` | 外部触发核心逻辑，HTTP / Provider / 蓝牙共用；含好友查询与列表接口 |
| `TriggerStore` | 请求状态持久化、幂等、忙状态、结果回调领取 |
| `BluetoothTriggerManager` | BLE GATT Server：广播、连接、MTU、写入缓冲、UTF-8 分片与终态 Notify |
| `BtProtocol` | 蓝牙协议常量、UUID、Token 常量时间比较与 JSON 响应构造 |
| `BtCommandHandler` | 把蓝牙指令分发到 `TriggerApi`，与 HTTP / Provider 行为一致 |
| `RootShell` | root 命令封装：唤醒解锁、后台拉起微信、执行内部 root 命令 |
| `WatchdogHelper` | 写入 `/data/adb/service.d` 看门狗脚本，每 30 秒检查心跳 / 端口并拉起服务 |
| `KeepAliveHelper` | 电池优化白名单、MIUI / HyperOS 自启动设置入口 |
| `BootReceiver` | 开机完成和 App 升级后恢复外部触发服务 |

## 外部触发注意事项

- 服务默认关闭；开启后有一个常驻前台通知，属于正常行为
- 后台拉起微信和锁屏解锁依赖 root，未授权时返回 `ROOT_DENIED`
- 自动解锁只处理滑动锁；有密码 / PIN / 图案锁时返回 `DEVICE_LOCKED`
- 触发时手机会亮屏并切到微信前台，通话界面会遮挡当前 App
- 按昵称匹配基于本地已导入的好友数据，好友变动后需重新导入
- 微信界面文案或「+」按钮描述变化时，模拟点击可能失败并返回 `CALL_BUTTON_NOT_FOUND`
- `SUCCESS` 仅代表本机已发起通话，不保证对方接听
- 蓝牙触发需要系统蓝牙已打开；Android 12+ 还需要「附近的设备」权限
- BLE 链路上的 Token 为明文 JSON，建议保持「仅允许已配对设备」开启并定期重置 Token
- 响应分片数量取决于 MTU，设备端协商 247 可以显著减少分片

## 保活 / 后台运行

外部触发是普通的 Android 前台服务，运行在模块 App 进程里。用户从最近任务划掉 App、系统内存不足回收进程、或 MIUI / HyperOS 的省电策略清理后台时，HTTP 端口都会消失，webhook 随之不可用。

模块从三个层面保活，建议全部开启：

| 层级 | 机制 | 能扛住什么 | 开启方式 |
| --- | --- | --- | --- |
| root 看门狗 | `/data/adb/service.d` 下的开机脚本，每 30 秒检查心跳文件（HTTP 模式同时检查端口），失联就广播唤醒 App 并拉起服务 | 划掉 App、系统回收、OEM 清理、重启手机 | 设置页「启用 root 看门狗」 |
| 服务生命周期 | `START_STICKY` + `onTaskRemoved` 闹钟 + 开机 / 升级广播 | 系统内存回收、划掉任务、App 升级 | 默认启用 |
| 系统白名单 | Doze 电池白名单、自启动、省电策略无限制 | Doze 断网、后台被杀 | 设置页「电池白名单」「自启动设置」 |

> 前提：手机已 root，且 root 管理器使用 `/data/adb/service.d`（Magisk、KernelSU、APatch 均支持）。设置页会自动写入脚本，不需要手动改文件。

### 设置页操作

打开 **⚙ → 外部触发**，在「保活 / 自启动」区域：

1. 打开「启用 root 看门狗」，首次会弹出 root 授权，授予后状态显示「运行中」
2. 点击「电池白名单」：有 root 时静默执行 `cmd deviceidle whitelist +<包名>` 和 `am set-standby-bucket <包名> active`；没有 root 时回退到系统弹窗
3. 点击「自启动设置」，在系统页面允许本应用自启动
4. 状态行会显示 `root` / `看门狗` / `电池白名单` 三项当前状态

开关外部触发时，如果已授权 root，会顺带加入电池白名单，并在看门狗开关打开的情况下自动安装看门狗。

### MIUI 13.0.10

按顺序设置：

1. **省电策略**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 省电策略 → **无限制**
2. **自启动**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 自启动 → 打开
3. **电池优化**：设置 → 隐私保护 → 特殊权限 → 电池优化 → 右上角切换到「所有应用」→ XpWeChatCall → **不优化**
   （也可以直接点设置页的「电池白名单」按钮）
4. **后台弹出界面**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 权限管理 → 后台弹出界面 → 允许
5. **通知**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 通知管理 → 允许通知（前台服务通知必须可见）
6. **锁定任务**：打开最近任务，长按 XpWeChatCall 卡片 → 锁定（出现小锁图标），避免「一键清理」时被清掉
7. **手机管家**：安全中心 → 垃圾清理 / 加速，把 XpWeChatCall 加入清理白名单（如果有该选项）

### HyperOS 1.0.7

HyperOS 把部分入口合并到了「应用管理」和「电池」中：

1. **省电策略**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 省电策略 → **无限制**
2. **自启动**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 自启动 → 打开
3. **电池优化**：设置 → 隐私与安全 → 特殊权限 → 电池优化 → 切换到「所有应用」→ XpWeChatCall → **不优化**
4. **后台弹出界面**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 其他权限 → 后台弹出界面 / 显示在其它应用上层 → 允许
5. **通知**：设置 → 应用设置 → 应用管理 → XpWeChatCall → 通知管理 → 允许通知；同时确认「常驻通知」没有被关闭
6. **锁定任务**：最近任务里长按 XpWeChatCall 卡片 → 锁定
7. **应用耗电管理**：设置 → 电池 → 应用耗电管理 → XpWeChatCall → 允许后台活动

### 验证保活是否生效

先确认服务在跑：

```bash
curl -H "Authorization: Bearer <TOKEN>" http://手机IP:8790/v1/health
```

然后依次做这些操作，每次等 30–60 秒再请求一次 `/v1/health`：

1. 按 Home 键让 App 进入后台
2. 从最近任务划掉 App
3. 用 `adb shell am kill com.zgcwkj.xpwechatcall` 杀掉进程（模拟低内存回收）
4. 重启手机

只要看门狗在运行，端口会在一分钟内恢复。注意：在系统设置里点「强行停止」会把整个包标记为 stopped，Linux 层面仍可能被看门狗拉起，但部分 ROM 会阻止，遇到这种情况需要手动再打开一次 App。

### 日志排查

| 内容 | 路径 |
| --- | --- |
| 看门狗脚本 | `/data/adb/service.d/xp_wechatcall_watchdog.sh` |
| 看门狗 PID | `/data/local/tmp/xp_wechatcall_watchdog.pid` |
| 看门狗重启日志 | `/data/local/tmp/xp_wechatcall_watchdog.log` |
| 模块日志 | App 私有目录 `logs/`，也可用 `adb logcat -s XpWeChatCall` |
| 服务心跳 | `/data/data/com.zgcwkj.xpwechatcall/files/service_heartbeat` |

查看看门狗日志：

```bash
adb shell su -c "cat /data/local/tmp/xp_wechatcall_watchdog.log"
```

手动确认脚本在运行：

```bash
adb shell su -c "cat /data/local/tmp/xp_wechatcall_watchdog.pid"
adb shell su -c "ps -A | grep xp_wechatcall_watchdog"
```

看门狗 v3 通过 App 私有目录下的 `files/service_heartbeat` 心跳文件判断服务是否存活，因此只开蓝牙、没有 HTTP 端口时也能正确保活。HTTP 模式下还会额外检查端口：脚本把端口转成十六进制匹配 `/proc/net/tcp` 和 `/proc/net/tcp6`（8790 → `2256`）。如果系统隐藏了 `/proc/net/tcp` 且心跳文件也无法读取，看门狗会一直认为服务不在并重复拉起服务，此时可先关闭看门狗，改用系统白名单方案。

### 注意事项

- **卸载 App 前先在设置页关闭「root 看门狗」**，否则脚本会留在 `/data/adb/service.d`，开机后不断尝试拉起已卸载的包
- 关闭通知栏服务（点「停止」）会同时把 HTTP 和蓝牙开关设为关闭，避免看门狗反复拉起
- 开启看门狗时会顺带加入电池白名单；Android 12+ 在未加入白名单时会阻止后台启动前台服务
- 看门狗通过显式广播唤醒 `BootReceiver`，再拉起服务；`TriggerService` 保持 `exported=false`，外部 App 无法直接启动或停止它
- 看门狗只负责让端口恢复监听，不负责解锁手机；通话仍需要 root 解锁或保持屏幕常亮
- 如果你使用 LSPosed 的「应用隐藏」或冻结类工具隐藏了模块 App，root 看门狗可能无法启动服务，需要把本应用加入白名单

## 兼容性说明

模块**不限定微信版本**，代码中没有任何版本判断。依赖项按风险从低到高：

| 风险 | 依赖项 | 失效表现 |
| --- | --- | --- |
| 低 | `com.tencent.wcdb.database.SQLiteDatabase`、`rcontact` 表及列名、`LauncherUI` / `ChattingUI` 类名与 `Chat_User` 参数 | 日志记录查询或挂钩失败 |
| 中 | 头像缓存路径 `MicroMsg/<用户哈希>/avatar/...`、`type` 位标志（`0x1` 好友 / `0x8` 公众号 / `0x20` 服务号） | 头像回退为首字图标；好友过滤数量异常 |
| 较高 | 界面文案「更多功能」「视频通话」「语音通话」 | 日志记录 `give up at step N` |

- `type` 位标志按微信 8.0.77 实测得出，换版本可能需要重新确认
- 界面文案匹配同时意味着微信语言须为中文
- 排查时开启日志查看 `/data/data/com.zgcwkj.xpwechatcall/files/logs/` 中的运行记录

## 编译

需要 JDK 17 和 Android SDK（compileSdk 36）。

```bash
cd src
gradlew assembleDebug
```

调试 APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装后在 Xposed/LSPosed 中启用模块，作用域勾选 `com.tencent.mm`，并重启微信。

### 签名 release APK

release 签名通过以下环境变量注入（任一项缺失或 keystore 文件不存在时，只产出 `app-release-unsigned.apk`）：

| 变量 | 说明 |
| --- | --- |
| `XP_KEYSTORE_FILE` | keystore 文件路径（绝对路径或相对 `src/app`） |
| `XP_KEYSTORE_PASSWORD` | keystore 密码 |
| `XP_KEY_ALIAS` | 密钥别名 |
| `XP_KEY_PASSWORD` | 密钥密码 |

PowerShell 示例：

```powershell
$env:XP_KEYSTORE_FILE = "D:\keys\release.jks"
$env:XP_KEYSTORE_PASSWORD = "******"
$env:XP_KEY_ALIAS = "release"
$env:XP_KEY_PASSWORD = "******"
cd src
.\gradlew assembleRelease
```

签名后的 APK 位于 `app/build/outputs/apk/release/app-release.apk`。

### GitHub Actions

仓库已配置 `.github/workflows/build-apk.yml`：

- push 到 `main` 或提交 PR：构建 debug APK，在运行页面的 **Artifacts** 中下载 `XpWeChatCall-debug-<sha>`
- push `v*` tag：构建已签名的 release APK，并自动创建 GitHub Release 附上 `XpWeChatCall-<tag>.apk`
- 手动触发：同时构建 debug 与已签名的 release APK，release APK 在 **Artifacts** 中下载

### 配置 release 签名 Secret

在仓库 **Settings → Secrets and variables → Actions** 中新增以下 4 个 Secret（名称固定）：

| Secret | 说明 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的 Base64 文本 |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 密钥别名 |
| `RELEASE_KEY_PASSWORD` | 密钥密码 |

生成 keystore 并转换为 Base64：

```bash
keytool -genkeypair -v -keystore release.jks -alias release \
  -keyalg RSA -keysize 2048 -validity 10000

# Linux（无换行）
base64 -w 0 release.jks > release.jks.base64

# macOS（默认不换行）
base64 -i release.jks -o release.jks.base64

# Windows PowerShell（无换行）
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content -NoNewline release.jks.base64
```

把 `release.jks.base64` 的内容整体粘贴到 `RELEASE_KEYSTORE_BASE64`。**不要**把 keystore 或 Base64 文本提交到仓库。

发布流程：

```bash
git tag v1.0.0
git push origin v1.0.0
```

流水线会校验 4 个 Secret 是否齐全；缺失时 release job 直接失败，不会产出 unsigned 包。构建完成后使用 `apksigner verify --print-certs` 校验签名，并上传 `XpWeChatCall-<tag>.apk`。

流水线使用 JDK 17、Android SDK 36 与 Gradle 9.6.1，无需在仓库中提交 `local.properties`。

## 许可证

[Apache License 2.0](LICENSE)
