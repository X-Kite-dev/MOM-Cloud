# JetLinks官方设备接入协议文档

## 1. 概述

JetLinks官方设备接入协议是一套用于物联网设备与JetLinks平台进行通信的标准化协议。本协议支持多种通信方式，包括MQTT、HTTP、TCP、UDP和CoAP等，开发者可以根据不同场景选择适合的接入方式。

**注意**：本协议仅用于参考自定义协议开发，在实际应用中应根据不同场景调整认证方式、加密等安全措施。

## 2. 协议支持

### 2.1 MQTT协议

MQTT是一种轻量级的发布/订阅消息传输协议，适用于资源受限的设备和低带宽、高延迟或不可靠的网络。

#### 2.1.1 主题格式

**下行主题（平台到设备）:**

- 读取设备属性: `/{productId}/{deviceId}/properties/read`
- 修改设备属性: `/{productId}/{deviceId}/properties/write`
- 调用设备功能: `/{productId}/{deviceId}/function/invoke`

**网关设备下行:**

- 读取子设备属性: `/{productId}/{deviceId}/child/{childDeviceId}/properties/read`
- 修改子设备属性: `/{productId}/{deviceId}/child/{childDeviceId}/properties/write`
- 调用子设备功能: `/{productId}/{deviceId}/child/{childDeviceId}/function/invoke`

**上行主题（设备到平台）:**

- 读取属性回复: `/{productId}/{deviceId}/properties/read/reply`
- 修改属性回复: `/{productId}/{deviceId}/properties/write/reply`
- 调用功能回复: `/{productId}/{deviceId}/function/invoke/reply`
- 上报设备事件: `/{productId}/{deviceId}/event/{eventId}`
- 上报设备属性: `/{productId}/{deviceId}/properties/report`
- 上报设备派生物模型: `/{productId}/{deviceId}/metadata/derived`

**网关设备上行:**

- 子设备上线消息: `/{productId}/{deviceId}/child/{childDeviceId}/connected`
- 子设备下线消息: `/{productId}/{deviceId}/child/{childDeviceId}/disconnect`
- 读取子设备属性回复: `/{productId}/{deviceId}/child/{childDeviceId}/properties/read/reply`
- 修改子设备属性回复: `/{productId}/{deviceId}/child/{childDeviceId}/properties/write/reply`
- 调用子设备功能回复: `/{productId}/{deviceId}/child/{childDeviceId}/function/invoke/reply`
- 上报子设备事件: `/{productId}/{deviceId}/child/{childDeviceId}/event/{eventId}`
- 上报子设备派生物模型: `/{productId}/{deviceId}/child/{childDeviceId}/metadata/derived`

#### 2.1.2 认证方式

MQTT使用用户名密码认证：

- 用户名: `secureId|timestamp`
- 密码: `md5(secureId|timestamp|secureKey)`

其中timestamp为时间戳，与服务器时间不能相差5分钟。

可以使用[认证生成工具](http://doc.jetlinks.cn/basics-guide/mqtt-auth-generator.html)生成认证信息。

#### 2.1.3 消息格式

MQTT消息采用JSON格式，以上报属性为例：

```json
{
  "properties": {
    "temperature": 36.5,
    "humidity": 65.3,
    "status": "online"
  },
  "timestamp": 1678344096015
}
```

调用功能示例：

```json
{
  "functionId": "open-door",
  "args": {
    "doorId": "front-door",
    "timeout": 30
  }
}
```

#### 2.1.4 MQTT适用场景

MQTT协议适用于以下场景：

- 带宽有限或网络不稳定的环境
- 需要实时推送和接收消息的应用
- 电池供电的低功耗设备
- 需要多种订阅/发布模式的应用

### 2.2 HTTP协议

HTTP接入使用Bearer认证，URL与MQTT的主题格式一致。

#### 2.2.1 请求示例

```http
POST /{productId}/{deviceId}/properties/report
Authorization: Bearer {产品或者设备中配置的Token}
Content-Type: application/json

{
  "properties":{
    "temp":38.5
  }
}
```

#### 2.2.2 响应格式

平台响应格式：

```json
{
  "success": true,
  "code": "success",
  "message": "上报成功",
  "timestamp": 1678344096015
}
```

如果请求失败，则返回：

```json
{
  "success": false,
  "code": "error_code",
  "message": "错误详情",
  "timestamp": 1678344096015
}
```

#### 2.2.3 HTTP适用场景

HTTP协议适用于以下场景：

- 只需要间歇性通信的场景
- 防火墙限制的企业网络环境
- 开发和调试阶段
- 需要基于请求/响应模式的应用
- 资源更丰富的设备

### 2.3 TCP协议

#### 2.3.1 报文格式

TCP采用二进制格式进行通信。报文格式如下：

- 前4字节（0-3字节）对应的32位整型值为接下来报文的长度
- 后续为报文数据

创建连接后，第一个数据包需要发送认证包，密钥需要在产品或设备中配置。

#### 2.3.2 消息流程

TCP连接的基本流程：

1. 设备建立TCP连接到服务器
2. 设备发送认证包（消息类型0x01）
3. 服务器验证并回复ACK消息（消息类型0x02）
4. 设备可以开始正常通信
5. 设备定期发送心跳包（消息类型0x00）保持连接

#### 2.3.3 TCP适用场景

TCP协议适用于以下场景：

- 需要可靠传输的应用
- 需要持久连接的场景
- 数据量较大且需要有序传输的场景
- 对延迟敏感的实时控制应用

### 2.4 UDP协议

#### 2.4.1 报文格式

UDP报文格式说明：

- 第0字节表示认证类型，目前固定为0x00
- 第1-n字节为密钥信息，编码使用STRING
- 后续为报文数据

UDP无需发送认证包，但需要每个报文中都包含密钥信息。除了ACK以外，平台下发的指令也都会包含认证密钥信息，用于设备侧校验请求。

#### 2.4.2 消息流程

UDP通信的基本流程：

1. 设备直接发送带有认证信息的消息
2. 服务器验证并处理消息
3. 服务器可能回复相关响应

#### 2.4.3 UDP适用场景

UDP协议适用于以下场景：

- 实时数据采集，如传感器数据上报
- 对延迟要求高但允许少量丢包的场景
- 广播/多播通信需求
- 资源受限的简单设备

### 2.5 CoAP协议

CoAP是一种为资源受限设备设计的网络传输协议，适用于IoT场景。JetLinks支持通过CoAP协议接入设备，支持标准的CoAP和DTLS安全传输。

#### 2.5.1 资源路径

CoAP资源路径与HTTP和MQTT主题格式类似：

- 上报属性: `/{productId}/{deviceId}/properties/report`
- 上报事件: `/{productId}/{deviceId}/event/{eventId}`

#### 2.5.2 CoAP适用场景

CoAP协议适用于以下场景：

- 极度资源受限的设备
- 需要HTTP类似语义但更轻量的场景
- 需要低功耗的电池供电设备
- 需要UDP传输但又需要可靠性保证的场景

## 3. 二进制协议规范

### 3.1 头信息

| 字节数 | 类型      |       字段        | 备注         |
|:---:|---------|:---------------:|------------|
|  n  | 自定      |      消息长度       | 平台配置项      |
|  1  | INT8    |      消息类型       | 见消息类型定义    |
|  8  | INT64   |     UTC时间戳      |            |
|  2  | INT16   |      消息序号       |            |
|  2  | INT16   |     设备ID长度      |            |
|  n  | STRING  |      设备ID       | 根据设备ID长度得出 |
|  n  | MESSAGE |    消息类型对应编码     |            |
|  2  | INT16   | secureKeyLength | TCP认证密钥长度  |
|  n  | STRING  |    secureKey    | 密钥         |

### 3.2 数据类型

所有数据类型均采用`大端`编码。

| Byte |  Type   | 编码规则                      |
|:----:|:-------:|---------------------------|
| 0x00 |  NULL   |                           |
| 0x01 | BOOLEAN | 1字节，0x00为false，其他为true    |
| 0x02 |  INT8   | 1字节整型                     |
| 0x03 |  INT16  | 2字节整型                     |
| 0x04 |  INT32  | 4字节整型                     |
| 0x05 |  INT64  | 8字节整型                     |
| 0x06 |  UINT8  | 1字节无符号整型                  |
| 0x07 | UINT16  | 2字节无符号整型                  |
| 0x08 | UINT32  | 4字节无符号整型                  |
| 0x09 |  FLOAT  | 4字节 IEEE 754浮点数           |
| 0x0a | DOUBLE  | 8字节 IEEE 754浮点数           |
| 0x0b | STRING  | 前2字节表示长度，后面为字符串内容（UTF8编码） |
| 0x0c | BINARY  | 前2字节表示长度，后面为二进制内容         |
| 0x0d |  ARRAY  | 前2字节表示数组长度，后面跟内容          |
| 0x0e | OBJECT  | 前2字节表示对象字段长度，后面为内容        |

### 3.3 消息类型定义

| Byte | Type               | 说明     | 消息格式                                          |
|:----:|:-------------------|--------|-----------------------------------------------|
| 0x00 | keepalive          | 心跳     |                                               |
| 0x01 | online             | 首次连接   | [STRING:密钥信息]                                 |
| 0x02 | ack                | 应答     | [应答码]                                         |
| 0x03 | reportProperty     | 上报属性   | [属性数据:OBJECT类型]                               |
| 0x04 | readProperty       | 读取属性   | [属性列表:ARRAY类型]                                |
| 0x05 | readPropertyReply  | 读取属性回复 | 成功:[0x01,属性数据:OBJECT类型]<br>失败:[0x00,错误码,错误消息] |
| 0x06 | writeProperty      | 修改属性   | [属性列表:OBJECT类型]                               |
| 0x07 | writePropertyReply | 修改属性回复 | 成功:[0x01,属性数据:OBJECT类型]<br>失败:[0x00,错误码,错误消息] |
| 0x08 | function           | 功能调用   | [功能ID:STRING类型,功能参数:OBJECT类型]                 |
| 0x09 | functionReply      | 功能调用回复 | 成功:[0x01,属性数据:OBJECT类型]<br>失败:[0x00,错误码,错误消息] |

## 4. 示例

### 4.1 设备上线示例

二进制格式：

```
000000270100000186c51a890f0001001331363531383533343133303332383934343634000561646d696e
```

Java代码示例：

```java
// 方式一：使用ByteBuf构建上线消息
String deviceId = "1651853413032894464";
String secureKey = "admin";
ByteBuf data = Unpooled
        .buffer()
        .writeByte(0x01)
        .writeLong(System.currentTimeMillis())
        .writeShort(1)
        .writeShort(deviceId.getBytes().length)
        .writeBytes(deviceId.getBytes())
        .writeShort(secureKey.getBytes().length)
        .writeBytes(secureKey.getBytes());
ByteBuf onlineBuf = Unpooled.buffer()
        .writeInt(data.readableBytes())
        .writeBytes(data);

// 方式二：使用jetlinks-core构建消息
DeviceOnlineMessage message = new DeviceOnlineMessage();
message.setDeviceId("1651853413032894464");
message.addHeader(BinaryDeviceOnlineMessage.loginToken, "admin");
message.setMessageId("1");
message.setTimestamp(1678344096015L);
ByteBuf byteBuf = BinaryMessageType.write(message, Unpooled.buffer());
ByteBuf buf = Unpooled
        .buffer()
        .writeInt(byteBuf.readableBytes())
        .writeBytes(byteBuf);
```

### 4.2 设备数据上报示例

二进制格式：

```
0000006C0300000186C567FA7900020013313635313835333431333033323839343436340001000474656d700B000433362e35000561646d696e
```

Java代码示例：

```java
// 方式一：直接构建
String deviceId = "1651853413032894464";
String secureKey = "admin";
String key = "temp";
String value = "36.5";
ByteBuf data = Unpooled
        .buffer()
        .writeByte(0x03)
        .writeLong(System.currentTimeMillis())
        .writeShort(2)
        .writeShort(deviceId.getBytes().length)
        .writeBytes(deviceId.getBytes())
        .writeShort(1)
        .writeShort(key.getBytes().length)
        .writeBytes(key.getBytes())
        .writeByte(0x0B)
        .writeShort(value.getBytes().length)
        .writeBytes(value.getBytes())
        .writeShort(secureKey.getBytes().length)
        .writeBytes(secureKey.getBytes());
ByteBuf reportBuf = Unpooled.buffer()
        .writeInt(data.readableBytes())
        .writeBytes(data);

// 方式二：使用jetlinks-core
ReportPropertyMessage message = new ReportPropertyMessage();
message.setDeviceId("1651853413032894464");
message.addHeader(BinaryDeviceOnlineMessage.loginToken, "admin");
message.setMessageId("2");
message.setProperties(Collections.singletonMap("temp", 32.88));
ByteBuf data = BinaryMessageType.write(message, Unpooled.buffer());
ByteBuf buf = Unpooled.buffer()
        .writeInt(data.readableBytes())
        .writeBytes(data);
```

### 4.3 MQTT消息示例

MQTT上报属性示例：

```java
MqttClient client = new MqttClient("tcp://localhost:1883", "device-123");
MqttConnectOptions options = new MqttConnectOptions();
options.setUserName("secureId|" + System.currentTimeMillis());
options.setPassword(DigestUtils.md5Hex("secureId|timestamp|secureKey").toCharArray());
client.connect(options);

JSONObject data = new JSONObject();
JSONObject properties = new JSONObject();
properties.put("temperature", 36.5);
properties.put("humidity", 75);
data.put("properties", properties);

client.publish("/productId/deviceId/properties/report",
               data.toJSONString().getBytes(),
               1,
               false);
```

### 4.4 HTTP消息示例

HTTP上报属性示例：

```java
String url = "http://localhost:8080/productId/deviceId/properties/report";
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer your-token-here")
        .POST(HttpRequest.BodyPublishers.ofString(
                "{\"properties\":{\"temperature\":36.5,\"humidity\":75}}"
        ))
        .build();

HttpResponse<String> response = client.send(request, 
                                          HttpResponse.BodyHandlers.ofString());
System.out.println(response.statusCode());
System.out.println(response.body());
```

## 5. 协议开发流程

### 5.1 自定义协议开发步骤

1. **确定通信方式与消息格式**
    - 选择合适的传输协议（MQTT、HTTP、TCP、UDP等）
    - 设计消息格式和编解码规则
    - 确定认证和加密方案

2. **实现协议接口**
    - 实现`DeviceMessageCodec`接口
      ```java
      public class CustomDeviceMessageCodec implements DeviceMessageCodec {
          @Override
          public Transport getSupportTransport() {
              return DefaultTransport.MQTT; // 或其他传输方式
          }
          
          @Nonnull
          @Override
          public Flux<DeviceMessage> decode(@Nonnull MessageDecodeContext context) {
              // 实现消息解码逻辑
          }
          
          @Nonnull
          @Override
          public Mono<? extends EncodedMessage> encode(@Nonnull MessageEncodeContext context) {
              // 实现消息编码逻辑
          }
      }
      ```
    - 实现`ProtocolSupportProvider`接口
      ```java
      public class CustomProtocolSupportProvider implements ProtocolSupportProvider {
          @Override
          public Mono<CompositeProtocolSupport> create(ServiceContext context) {
              CompositeProtocolSupport support = new CompositeProtocolSupport();
              support.setId("custom-protocol");
              support.setName("自定义协议");
              support.setDescription("自定义设备接入协议");
              
              // 配置消息编解码器
              support.addMessageCodecSupport(new CustomDeviceMessageCodec());
              
              // 配置设备认证器
              support.addAuthenticator(DefaultTransport.MQTT, new CustomAuthenticator());
              
              // 配置元数据编解码器
              support.setMetadataCodec(new CustomMetadataCodec());
              
              return Mono.just(support);
          }
      }
      ```

3. **注册协议**
    - 通过SPI机制注册协议
    - 在`META-INF/services/org.jetlinks.core.spi.ProtocolSupportProvider`文件中添加实现类全名

4. **测试与验证**
    - 使用JetLinks设备模拟器测试协议
    - 验证消息收发和处理流程

### 5.2 协议调用流程

JetLinks平台中协议调用流程如下：

1. **协议初始化**
    - 平台启动时通过SPI机制发现并加载协议
    - 调用`ProtocolSupportProvider.create()`创建协议支持实例
    - 注册协议到协议管理器

2. **设备连接处理流程**
    - 设备发起连接请求
    - 平台根据协议类型和传输方式选择合适的协议支持
    - 调用协议的认证器进行设备认证
    - 认证成功后建立会话

3. **消息处理流程**
    - 设备发送消息到平台
    - 平台根据会话信息找到对应的协议
    - 调用协议的`DeviceMessageCodec.decode()`解码消息
    - 平台处理解码后的消息
    - 平台需要回复消息时，调用`DeviceMessageCodec.encode()`编码消息
    - 编码后的消息发送给设备

4. **会话维护与断开**
    - 平台定期检查会话状态
    - 设备断开连接或会话超时时，平台触发设备离线事件
    - 释放相关资源

## 6. 安全建议

1. 在生产环境中应启用安全传输层，如TLS/DTLS
2. 为设备分配唯一的认证凭证，避免使用通用密钥
3. 定期更新密钥和认证信息
4. 实现消息有效性验证，防止重放攻击
5. 对敏感数据进行加密处理

## 7. 参考资源

- [JetLinks文档中心](http://doc.jetlinks.cn/)
- [MQTT主题说明](https://hanta.yuque.com/px7kg1/yfac2l/sr0metyagzmhbtm7)
- [设备模拟器](http://github.com/jetlinks/device-simulator)
- [JetLinks核心库](https://github.com/jetlinks/jetlinks-core) 