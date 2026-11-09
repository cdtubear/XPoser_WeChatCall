# 蓝牙触发（BLE）

模块支持把手机当作 **BLE 外设（GATT Server）**，外部设备（ESP32、nRF52、Arduino、nRF Connect 等）连接后写入一条 JSON 指令，即可发起微信通话，并通过 Notify 收到即时响应和最终结果。

- 默认关闭，不影响 HTTP、ContentProvider、桌面快捷方式和桌面控件
- 与 HTTP 接口共用同一套好友匹配、忙状态、幂等、超时和结果存储逻辑
- 使用 Nordic UART Service 兼容 UUID，常见的 BLE UART 示例可以直接复用
- 每条指令都必须携带 Token；可选只允许系统蓝牙中已配对的设备

## 启用

1. 打开 App 右上角 **⚙ → 外部触发**
2. 在「蓝牙触发（BLE）」区域打开 **启用蓝牙触发**
3. Android 12+ 会申请「附近的设备」权限（`BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`），请允许
4. 打开手机系统蓝牙
5. 如需要更高吞吐量，在设备端连接后协商 MTU（推荐 247）

启用后，前台通知会显示 `BLE 广播中`，设置页会显示 BLE 服务、广播和连接状态。

> HTTP 与蓝牙是独立开关：只开蓝牙时服务仍会运行，但不会监听 HTTP 端口。两者都关闭时服务停止。

## BLE 参数

| 项目 | 值 |
| --- | --- |
| Service UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX UUID（设备写入） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX UUID（设备订阅 Notify） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |
| 广播 | 可连接，广播包包含 Service UUID，扫描响应包含手机蓝牙名称 |
| RX 属性 | Write / Write Without Response |
| TX 属性 | Notify |

连接后必须**先订阅 TX 的 Notify**（向 CCCD 写入 `01 00`），否则手机不会发送响应。

## 帧格式

- 指令为 **UTF-8 JSON**
- 推荐以换行符 `\n` 结尾；一条写入可以包含多条以 `\n` 分隔的指令
- 也支持一次写入完整 JSON（以 `{` 开头、`}` 结尾），方便 nRF Connect 手动测试
- 单条指令上限 **1024 字节**，超限会返回 `BAD_REQUEST` 并清空当前缓冲
- 写入缓冲按设备地址隔离，不同设备互不影响

示例：

```json
{"token":"<TOKEN>","action":"call","wxid":"wxid_abc123","type":"video"}
```

## 鉴权与安全

- 所有指令都必须包含 `token`，值为设置页「访问 Token」中的字符串
- Token 比较使用常量时间算法，避免通过响应时间猜测
- 设置页「仅允许已配对设备」默认开启：
  - 开启时，未在系统蓝牙设置中配对的设备发送指令会收到 `BT_NOT_BONDED`
  - 关闭时仍会校验 Token，但允许任意 BLE 设备连接
- Token 在 BLE 链路上以明文 JSON 传输，建议：
  - 保持「仅允许已配对设备」开启
  - 在系统蓝牙中完成配对，让链路启用加密
  - 不要把 Token 写死到公开固件或提交到仓库
  - 定期在设置页重置 Token

## 动作

每条指令的 `action` 可选值：

| action | 说明 |
| --- | --- |
| `call` | 发起视频 / 语音通话（默认） |
| `query` | 按 `request_id` 查询请求状态 |
| `health` | 查看服务、好友、蓝牙状态 |
| `friends` | 获取好友列表，支持分页与关键字 |
| `resolve` | 按昵称 / 备注查 wxid |
| `ping` | 连通性测试 |
| `help` | 返回 UUID、帧格式和动作列表 |

### call

字段与 HTTP `/v1/call` 一致：

| 字段 | 说明 |
| --- | --- |
| `wxid` / `name` | 二选一；同时提供时以 `wxid` 为准 |
| `type` | `video`（默认）或 `voice` |
| `request_id` | 可选，调用方自定义，便于幂等与查询；最长 64 字符 |
| `timeout_sec` | 可选，5–120 秒 |
| `pick_first` | 最高分有多个好友时是否直接取第一个，默认 `false` |
| `callback_url` | 可选，HTTP 结果回调；蓝牙场景一般不需要 |

请求：

```json
{"token":"<TOKEN>","action":"call","name":"张三","type":"video"}
```

即时响应：

```json
{"ok":true,"request_id":"bt_1762300000000_1a2b3c","state":"ACCEPTED","wxid":"wxid_abc123","type":"video"}
```

`SUCCESS` 只表示本机已点击到通话界面，不代表对方接听。

### query

```json
{"token":"<TOKEN>","action":"query","request_id":"bt_1762300000000_1a2b3c"}
```

### health

```json
{"token":"<TOKEN>","action":"health"}
```

响应中与蓝牙相关的字段：

| 字段 | 说明 |
| --- | --- |
| `http_enabled` / `http_running` | HTTP 开关 / 端口是否在监听 |
| `bt_enabled` | 蓝牙触发开关 |
| `bt_require_bond` | 是否仅允许已配对设备 |
| `bt_running` | GATT Server 是否已启动 |
| `bt_advertising` | 是否正在广播 |
| `bt_connected` | 当前连接数 |
| `bt_last_command` | 最近一条指令 |
| `bt_last_result` | 最近一条终态结果 |
| `bt_service_uuid` / `bt_rx_uuid` / `bt_tx_uuid` | BLE UUID |

### friends

```json
{"token":"<TOKEN>","action":"friends","offset":0,"limit":20,"keyword":"张"}
```

`limit` 小于等于 0 表示返回全部。大列表建议先协商 MTU，否则响应会被分成较多分片。

### resolve

```json
{"token":"<TOKEN>","action":"resolve","name":"张三","fuzzy":true,"limit":20}
```

### ping / help

```json
{"token":"<TOKEN>","action":"ping"}
```

```json
{"ok":true,"pong":true,"time":1762300000000}
```

```json
{"token":"<TOKEN>","action":"help"}
```

## 响应与分片

响应是 UTF-8 JSON，通过 TX Notify 发送。

- 如果响应字节数不超过当前 MTU 可承载长度（`MTU - 3`），直接发送完整 JSON
- 超过时自动分片，每片格式为：

```text
@序号/总数:内容
```

例如：

```text
@1/3:{"ok":true,"data":{"total":128,
@2/3:"friends":[{"wxid":"wxid_a
@3/3":...,"display":"张三"}]}}
```

设备端按序号拼接 `:` 之后的全部内容即可。分片按 UTF-8 字节切分，不会截断中文等多字节字符。

默认 MTU 为 23 时，每片有效内容只有约 8 字节，大响应会产生很多分片。设备端连接后请主动协商 MTU：

- Android 客户端：`gatt.requestMtu(247)`
- ESP32 Arduino：`client->setMTU(247)`
- nRF Connect：打开连接后会在日志中显示协商后的 MTU

> 只有已订阅 TX Notify 的设备才会收到响应和终态。写入成功只代表 GATT 层写入成功，不代表业务成功。

## 终态结果

发起通话后，模块会在状态进入终态（`SUCCESS` 或 `FAILED`）时额外推送一条事件：

```json
{"event":"result","ok":true,"data":{"request_id":"bt_1762300000000_1a2b3c","state":"SUCCESS","wxid":"wxid_abc123","type":"video","detail":"","updated_at":1762300005000}}
```

- 结果会发送给发起该 `request_id` 的设备
- 如果服务重启导致映射丢失，会退化为广播给所有已订阅设备
- 终态事件同样可能分片，处理方式与普通响应一致
- 也可以随时用 `query` 主动查询，结果默认保留 30 分钟

## nRF Connect 测试

1. 打开 nRF Connect → Scanner，按 Service UUID 或手机蓝牙名称找到设备
2. 点击 **Connect**
3. 展开 Nordic UART Service
4. 点击 TX 特征右侧的订阅按钮（三个箭头），确认 CCCD 已开启 Notify
5. 点击 RX 特征右侧的上传箭头，选择 **UTF-8**，输入：

```json
{"token":"<TOKEN>","action":"ping"}
```

6. 点击 **Send**（Write 或 Write Without Response 均可）
7. 在 TX 通知中查看响应

如果收不到响应，先确认 TX 是否已订阅，再检查 Token 和「仅允许已配对设备」设置。

## ESP32 / Arduino 示例

下面示例使用 ESP32 Arduino Core 的 BLE 客户端连接手机，发送 `ping`，并打印通知内容。实际项目中请把 Token 和好友信息换成自己的值。

```cpp
#include <BLEDevice.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>

static const char* SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* RX_UUID      = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char* TX_UUID      = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
static const char* TOKEN        = "<TOKEN>";

BLEClient* client = nullptr;
BLERemoteCharacteristic* rxChar = nullptr;

static void onNotify(BLERemoteCharacteristic* ch, uint8_t* data,
                     size_t length, bool isNotify) {
  Serial.write(data, length);
  Serial.println();
}

class ScanCallbacks : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice dev) override {
    if (!dev.haveServiceUUID() || !dev.isAdvertisingService(BLEUUID(SERVICE_UUID))) {
      return;
    }
    BLEDevice::getScan()->stop();

    client = BLEDevice::createClient();
    if (!client->connect(&dev)) {
      Serial.println("connect failed");
      return;
    }
    client->setMTU(247);

    auto service = client->getService(BLEUUID(SERVICE_UUID));
    if (service == nullptr) {
      Serial.println("service not found");
      return;
    }
    auto txChar = service->getCharacteristic(BLEUUID(TX_UUID));
    rxChar = service->getCharacteristic(BLEUUID(RX_UUID));
    if (txChar == nullptr || rxChar == nullptr) {
      Serial.println("characteristic not found");
      return;
    }

    // 订阅 TX Notify（Arduino Core 会自动写 CCCD）
    txChar->registerForNotify(onNotify);

    String cmd = String("{\"token\":\"") + TOKEN +
                 "\",\"action\":\"ping\"}\n";
    rxChar->writeValue(cmd.c_str(), cmd.length(), true);
    Serial.println("ping sent");
  }
};

void setup() {
  Serial.begin(115200);
  BLEDevice::init("XpTriggerClient");
  auto scan = BLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(new ScanCallbacks());
  scan->setActiveScan(true);
  scan->start(10, false);
}

void loop() {
  delay(1000);
}
```

发送通话指令时，把 `cmd` 换成：

```cpp
String cmd = String("{\"token\":\"") + TOKEN +
             "\",\"action\":\"call\",\"name\":\"张三\",\"type\":\"video\"}\n";
```

## 错误码

| 错误码 | 说明 |
| --- | --- |
| `BAD_REQUEST` | JSON 无效、缺少字段、帧过大或未知 action |
| `UNAUTHORIZED` | Token 错误 |
| `DISABLED` | 蓝牙触发未开启 |
| `BT_NOT_BONDED` | 设备未在系统蓝牙中配对（开启「仅允许已配对设备」时） |
| `BT_UNAVAILABLE` | 蓝牙不可用（保留错误码） |
| `BUSY` | 已有进行中的通话请求 |
| `FRIEND_NOT_FOUND` | 找不到好友 |
| `FRIEND_AMBIGUOUS` | 匹配到多个好友，需改用 `wxid` 或 `pick_first` |
| `NOT_SYNCED` | 尚未导入好友数据 |
| `ROOT_DENIED` | root 未授权 |
| `DEVICE_LOCKED` | 设备有密码 / PIN / 图案锁，无法自动解锁 |
| `LAUNCH_BLOCKED` | 后台拉起微信失败 |
| `CHAT_OPEN_FAILED` | 打开聊天页失败 |
| `CALL_BUTTON_NOT_FOUND` | 未找到通话按钮（微信界面文案变化） |
| `TIMEOUT` | 等待终态超时 |
| `INTERNAL` | 内部异常 |

错误响应示例：

```json
{"ok":false,"error":"UNAUTHORIZED","message":"invalid token"}
```

## 排错

| 现象 | 排查 |
| --- | --- |
| 搜不到设备 | 确认蓝牙触发已开启、系统蓝牙已打开、前台通知显示 `BLE 广播中`；部分机型需要关闭再打开蓝牙 |
| 能连接但写入失败 | 确认写入的是 RX 特征；确认 Android 12+ 已授予「附近的设备」权限 |
| 写入成功但收不到响应 | 确认已订阅 TX Notify；确认 Token 正确；查看 `adb logcat -s XpWeChatCall` |
| 返回 `BT_NOT_BONDED` | 在系统蓝牙设置中先配对，或关闭「仅允许已配对设备」 |
| 响应分片很多 | 设备端协商更大的 MTU，推荐 247 |
| 通话返回 `DEVICE_LOCKED` | 自动解锁仅支持滑动锁；有密码时请保持屏幕常亮或手动解锁 |
| 服务只开蓝牙但被杀后不恢复 | 打开 root 看门狗；v3 看门狗通过 `files/service_heartbeat` 心跳判断存活，不再依赖 HTTP 端口 |

相关日志：

```bash
adb logcat -s XpWeChatCall
```

心跳文件：

```text
/data/data/com.zgcwkj.xpwechatcall/files/service_heartbeat
```

## 与 HTTP 接口的关系

| 能力 | HTTP | BLE |
| --- | --- | --- |
| 发起通话 | `POST /v1/call` | `{"action":"call"}` |
| 查询结果 | `GET /v1/result/{id}` | `{"action":"query"}` |
| 好友列表 | `GET /v1/friends` | `{"action":"friends"}` |
| 昵称解析 | `POST /v1/resolve` | `{"action":"resolve"}` |
| 健康检查 | `GET /v1/health` | `{"action":"health"}` |
| 鉴权 | `Authorization: Bearer <TOKEN>` | 指令中的 `token` |
| 结果通知 | 回调 URL / 轮询 | TX Notify 终态事件 |

两个入口共用 `TriggerApi` 和 `TriggerStore`，因此同一个 `request_id` 的幂等、忙状态和结果查询行为一致。
