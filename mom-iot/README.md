# JetLinks 组件开发文档

## 目录

- [网络组件模块（network-component）](#网络组件模块network-component)
    - [概述](#1-概述)
    - [核心接口](#2-核心接口)
    - [网络组件实现](#3-网络组件实现)
    - [开发新的网络组件](#4-开发新的网络组件)
    - [网络组件使用示例](#5-网络组件使用示例)
- [网关模块（gateway-component）](#网关模块gateway-component)
    - [概述](#1-概述-1)
    - [核心接口](#2-核心接口-1)
    - [抽象实现](#3-抽象实现)
    - [开发新的设备网关](#4-开发新的设备网关)
    - [设备网关使用示例](#5-设备网关使用示例)
- [总结](#总结)

## 网络组件模块（network-component）

### 1. 概述

网络组件模块是 JetLinks 项目中的核心组件，用于处理各种网络通信协议。该模块提供了抽象的网络接口和实现，支持多种网络协议，如
TCP、MQTT、HTTP 等。

### 2. 核心接口

#### 2.1 Network

`Network` 接口是所有网络组件的根接口，定义了基本的网络组件功能：

```java
public interface Network {
    // 获取唯一标识ID
    String getId();
    
    // 获取网络类型
    NetworkType getType();
    
    // 关闭网络组件
    void shutdown();
    
    // 判断网络组件是否存活
    boolean isAlive();
    
    // 是否自动重新加载
    boolean isAutoReload();
}
```

#### 2.2 NetworkType

`NetworkType` 接口定义了网络组件类型的分类：

```java
public interface NetworkType {
    // 获取类型唯一标识
    String getId();
    
    // 获取类型名称
    default String getName() {
        return getId();
    }
    
    // 根据ID创建NetworkType
    static NetworkType of(String id) {
        return () -> id;
    }
    
    // 获取所有支持的网络组件类型
    static List<NetworkType> getAll() {
        return NetworkTypes.get();
    }
    
    // 根据ID查找NetworkType
    static Optional<NetworkType> lookup(String id) {
        return NetworkTypes.lookup(id);
    }
}
```

#### 2.3 NetworkProvider

`NetworkProvider` 接口用于创建和管理网络组件：

```java
public interface NetworkProvider<P> {
    // 获取网络类型
    NetworkType getType();
    
    // 创建网络组件
    Network createNetwork(P properties);
    
    // 重新加载网络组件
    Network reload(Network network, P properties);
    
    // 获取配置元数据
    ConfigMetadata getConfigMetadata();
    
    // 创建网络配置
    P createConfig(NetworkProperties properties);
    
    // 是否可复用
    default boolean isReusable() {
        return false;
    }
}
```

#### 2.4 NetworkManager

`NetworkManager` 接口用于统一管理网络组件：

```java
public interface NetworkManager {
    // 获取指定类型和ID的网络组件
    <T extends Network> T getNetwork(NetworkType type, String id);
    
    // 获取所有网络组件
    List<Network> getNetworks();
    
    // 获取所有网络组件提供商
    List<NetworkProvider<?>> getProviders();
    
    // 根据类型获取提供商
    Optional<NetworkProvider<?>> getProvider(String type);
    
    // 重新加载网络组件
    void reload(NetworkType type, String id);
    
    // 停止网络组件
    void shutdown(NetworkType type, String id);
    
    // 销毁网络组件
    void destroy(NetworkType type, String id);
}
```

### 3. 网络组件实现

项目中已经实现了多种网络协议:

- **TCP组件**: 提供TCP客户端和服务器实现
- **MQTT组件**: 提供MQTT客户端和服务器实现
- **HTTP组件**: 提供HTTP客户端和服务器实现

### 4. 开发新的网络组件

要开发新的网络组件，需要按照以下步骤进行：

1. **定义网络类型**：创建一个新的 `NetworkType` 实现。
2. **实现网络接口**：根据特定协议实现 `Network` 接口。
3. **创建网络提供商**：实现 `NetworkProvider` 接口，用于创建和管理网络组件实例。
4. **注册网络提供商**：将网络提供商注册到系统中，以便被 `NetworkManager` 发现和管理。

示例：

```java
// 定义网络类型
public class MyNetworkType implements NetworkType {
    @Override
    public String getId() {
        return "my-network";
    }
    
    @Override
    public String getName() {
        return "My Network";
    }
}

// 实现网络接口
public class MyNetwork implements Network {
    private final String id;
    private boolean alive = true;
    
    public MyNetwork(String id) {
        this.id = id;
    }
    
    @Override
    public String getId() {
        return id;
    }
    
    @Override
    public NetworkType getType() {
        return new MyNetworkType();
    }
    
    @Override
    public void shutdown() {
        alive = false;
    }
    
    @Override
    public boolean isAlive() {
        return alive;
    }
    
    @Override
    public boolean isAutoReload() {
        return true;
    }
}

// 创建网络提供商
public class MyNetworkProvider implements NetworkProvider<Map<String, Object>> {
    @Override
    public NetworkType getType() {
        return new MyNetworkType();
    }
    
    @Override
    public Network createNetwork(Map<String, Object> properties) {
        // 根据配置创建网络组件
        String id = (String) properties.get("id");
        return new MyNetwork(id);
    }
    
    @Override
    public Network reload(Network network, Map<String, Object> properties) {
        // 重新加载网络组件
        return createNetwork(properties);
    }
    
    @Override
    public ConfigMetadata getConfigMetadata() {
        // 返回配置元数据
        return null;
    }
    
    @Override
    public Map<String, Object> createConfig(NetworkProperties properties) {
        // 创建网络配置
        return properties.getProperties();
    }
    
    @Override
    public boolean isReusable() {
        return false;
    }
}

// 注册网络提供商
public class NetworkProviderRegistrar {
    public static void registerNetworkProvider(NetworkManager networkManager) {
        networkManager.getProviders().add(new MyNetworkProvider());
    }
}
```

### 5. 网络组件使用示例

```java
// 获取网络管理器
NetworkManager networkManager = NetworkManagerFactory.getInstance();

// 获取网络类型
NetworkType networkType = NetworkType.of("my-network");

// 创建网络配置
Map<String, Object> config = new HashMap<>();
config.put("id", "my-network-instance");
config.put("host", "localhost");
config.put("port", 8080);

// 获取提供商
Optional<NetworkProvider<?>> providerOpt = networkManager.getProvider("my-network");
if (providerOpt.isPresent()) {
    NetworkProvider provider = providerOpt.get();
    
    // 创建网络组件
    Network network = provider.createNetwork(config);
    
    // 使用网络组件
    if (network.isAlive()) {
        // 执行网络操作
        System.out.println("网络组件 " + network.getId() + " 正在运行");
    }
    
    // 关闭网络组件
    networkManager.shutdown(networkType, "my-network-instance");
}
```

## 网关模块（gateway-component）

### 1. 概述

网关模块是用于统一管理设备连接、状态以及消息收发的组件。它提供了抽象接口和实现，用于连接和管理各种设备。

### 2. 核心接口

#### 2.1 DeviceGateway

`DeviceGateway` 接口是设备网关的核心接口，用于统一管理设备连接、状态及消息收发：

```java
public interface DeviceGateway {
    // 获取网关ID
    String getId();
    
    // 获取消息监听器
    MessageListener getMessageListener();
    
    // 设置消息监听器
    void setMessageListener(MessageListener listener);
    
    // 启动网关
    void startup();
    
    // 暂停网关
    void pause();
    
    // 关闭网关
    void shutdown();
    
    // 检查网关是否存活
    default boolean isAlive() {
        return true;
    }
    
    // 检查网关是否已启动
    default boolean isStarted() {
        return getState() == GatewayState.started;
    }
    
    // 获取网关状态
    default GatewayState getState() {
        return GatewayState.started;
    }
    
    // 注册状态变更监听器
    default void doOnStateChange(BiConsumer<GatewayState, GatewayState> listener) {
    }
    
    // 注册关闭时的处理逻辑
    default void doOnShutdown(Runnable runnable) {
        doOnStateChange((before, after) -> {
            if (after == GatewayState.shutdown) {
                runnable.run();
            }
        });
    }
    
    // 消息监听器接口
    interface MessageListener {
        void onMessage(Message message);
    }
}
```

#### 2.2 GatewayState

网关状态枚举，定义了网关的可能状态：

```java
public enum GatewayState {
    starting,   // 正在启动
    started,    // 已启动
    paused,     // 已暂停
    shutdown    // 已关闭
}
```

#### 2.3 DeviceGatewayManager

`DeviceGatewayManager` 接口用于统一管理设备接入网关等相关信息：

```java
public interface DeviceGatewayManager {
    // 获取接入网关
    DeviceGateway getGateway(String id);
    
    // 重新加载网关
    void reload(String gatewayId);
    
    // 停止网关
    void shutdown(String gatewayId);
    
    // 启动网关
    void start(String id);
    
    // 获取接入网关通道信息
    ChannelInfo getChannel(String channel, String channelId);
    
    // 获取全部的设备接入网关提供商
    List<DeviceGatewayProvider> getProviders();
    
    // 根据接入提供商标识获取设备接入提供商接口
    Optional<DeviceGatewayProvider> getProvider(String provider);
}
```

#### 2.4 DeviceGatewayProvider

`DeviceGatewayProvider` 接口用于提供对各种设备网关的支持：

```java
public interface DeviceGatewayProvider {
    String CHANNEL_NETWORK = "network";
    
    // 获取唯一标识
    String getId();
    
    // 获取名称
    String getName();
    
    // 获取接入说明
    default String getDescription() {
        return null;
    }
    
    // 获取接入通道
    default String getChannel() {
        return CHANNEL_NETWORK;
    }
    
    // 获取排序
    default int getOrder() {
        return Integer.MAX_VALUE;
    }
    
    // 获取传输协议
    Transport getTransport();
    
    // 创建设备网关
    DeviceGateway createDeviceGateway(DeviceGatewayProperties properties);
    
    // 重新加载网关
    default DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        return gateway;
    }
}
```

#### 2.5 DeviceGatewayProperties

设备网关属性外观类，用于转换设备网关属性数据：

```java
public class DeviceGatewayProperties implements ValueObject {
    private String id;
    private String name;
    private String description;
    private String provider;
    private String channelId;
    private String protocol;
    private String transport;
    private Map<String, Object> configuration = new HashMap<>();
    
    @Override
    public Map<String, Object> values() {
        return configuration;
    }
    
    // getters and setters
}
```

### 3. 抽象实现

`AbstractDeviceGateway` 提供了 `DeviceGateway` 接口的基本实现：

```java
public abstract class AbstractDeviceGateway implements DeviceGateway {
    private final String id;
    private volatile GatewayState state = GatewayState.shutdown;
    private final List<BiConsumer<GatewayState, GatewayState>> stateListeners = new CopyOnWriteArrayList<>();
    protected final DeviceGatewayMonitor monitor;
    private MessageListener messageListener;
    
    public AbstractDeviceGateway(String id) {
        this.id = id;
        this.monitor = GatewayMonitors.getDeviceGatewayMonitor(id);
    }
    
    @Override
    public final String getId() {
        return id;
    }
    
    @Override
    public MessageListener getMessageListener() {
        return messageListener;
    }
    
    @Override
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }
    
    @Override
    public final synchronized void startup() {
        if (state == GatewayState.paused) {
            changeState(GatewayState.started);
            return;
        }
        if (state == GatewayState.started || state == GatewayState.starting) {
            return;
        }
        changeState(GatewayState.starting);
        try {
            doStartup();
            changeState(GatewayState.started);
        } catch (Exception e) {
            changeState(GatewayState.shutdown);
            throw e;
        }
    }
    
    @Override
    public final void pause() {
        changeState(GatewayState.paused);
    }
    
    @Override
    public final void shutdown() {
        GatewayState oldState = state;
        if (oldState == GatewayState.shutdown) {
            return;
        }
        changeState(GatewayState.shutdown);
        doShutdown();
    }
    
    // 子类需要实现的关闭逻辑
    protected abstract void doShutdown();
    
    // 子类需要实现的启动逻辑
    protected abstract void doStartup();
    
    // 状态变更处理
    protected synchronized final void changeState(GatewayState target) {
        GatewayState old = this.state;
        this.state = target;
        if (target == old) {
            return;
        }
        for (BiConsumer<GatewayState, GatewayState> consumer : stateListeners) {
            try {
                consumer.accept(old, this.state);
            } catch (Throwable error) {
                // 记录日志
            }
        }
    }
    
    // 处理接收到的消息
    protected void handleMessage(Message message) {
        if (messageListener != null) {
            try {
                messageListener.onMessage(message);
            } catch (Exception e) {
                // 记录日志
            }
        }
    }
    
    @Override
    public final GatewayState getState() {
        return state;
    }
    
    @Override
    public final void doOnStateChange(BiConsumer<GatewayState, GatewayState> listener) {
        stateListeners.add(listener);
    }
    
    @Override
    public final boolean isAlive() {
        return state == GatewayState.started || state == GatewayState.starting;
    }
}
```

### 4. 开发新的设备网关

要开发新的设备网关，需要按照以下步骤进行：

1. **继承AbstractDeviceGateway**：实现抽象类中的抽象方法。
2. **实现设备网关提供商**：创建 `DeviceGatewayProvider` 接口的实现类。
3. **注册设备网关提供商**：将设备网关提供商注册到系统中。

示例：

```java
// 继承AbstractDeviceGateway
public class MyDeviceGateway extends AbstractDeviceGateway {
    private final Map<String, Object> config;
    private Thread messageProcessor;
    private boolean running = false;
    
    public MyDeviceGateway(String id, Map<String, Object> config) {
        super(id);
        this.config = config;
    }
    
    @Override
    protected void doStartup() {
        // 实现启动逻辑
        running = true;
        messageProcessor = new Thread(() -> {
            while (running) {
                try {
                    // 模拟接收消息
                    Thread.sleep(1000);
                    handleMessage(createDummyMessage());
                } catch (InterruptedException e) {
                    // 线程被中断
                    break;
                }
            }
        });
        messageProcessor.start();
    }
    
    @Override
    protected void doShutdown() {
        // 实现关闭逻辑
        running = false;
        if (messageProcessor != null) {
            messageProcessor.interrupt();
            try {
                messageProcessor.join(5000); // 等待最多5秒钟
            } catch (InterruptedException e) {
                // 被中断
            }
            messageProcessor = null;
        }
    }
    
    private Message createDummyMessage() {
        // 创建一个示例消息
        return new SimpleMessage(getId(), System.currentTimeMillis(), "测试消息内容");
    }
    
    // 简单消息实现
    private static class SimpleMessage implements Message {
        private final String deviceId;
        private final long timestamp;
        private final String payload;
        
        public SimpleMessage(String deviceId, long timestamp, String payload) {
            this.deviceId = deviceId;
            this.timestamp = timestamp;
            this.payload = payload;
        }
        
        // 实现Message接口的方法
    }
}

// 实现设备网关提供商
public class MyDeviceGatewayProvider implements DeviceGatewayProvider {
    @Override
    public String getId() {
        return "my-device-gateway";
    }
    
    @Override
    public String getName() {
        return "My Device Gateway";
    }
    
    @Override
    public String getDescription() {
        return "A custom device gateway implementation";
    }
    
    @Override
    public String getChannel() {
        return DeviceGatewayProvider.CHANNEL_NETWORK;
    }
    
    @Override
    public int getOrder() {
        return 100;
    }
    
    @Override
    public Transport getTransport() {
        return Transport.of("my-transport");
    }
    
    @Override
    public DeviceGateway createDeviceGateway(DeviceGatewayProperties properties) {
        String id = properties.getId();
        Map<String, Object> config = properties.values();
        return new MyDeviceGateway(id, config);
    }
    
    @Override
    public DeviceGateway reloadDeviceGateway(DeviceGateway gateway, DeviceGatewayProperties properties) {
        gateway.shutdown();
        return createDeviceGateway(properties);
    }
}

// 注册设备网关提供商
public class DeviceGatewayProviderRegistrar {
    public static void registerDeviceGatewayProvider(DeviceGatewayManager gatewayManager) {
        // 通过Java SPI机制或者Spring配置注册
    }
}
```

### 5. 设备网关使用示例

```java
// 获取设备网关管理器
DeviceGatewayManager gatewayManager = DeviceGatewayManagerFactory.getInstance();

// 创建设备网关配置
DeviceGatewayProperties properties = new DeviceGatewayProperties();
properties.setId("my-gateway-instance");
properties.setName("My Gateway Instance");
properties.setProvider("my-device-gateway");
properties.setChannelId("my-network-instance");
properties.setProtocol("my-protocol");
properties.setTransport("my-transport");
Map<String, Object> config = new HashMap<>();
config.put("option1", "value1");
config.put("option2", "value2");
properties.setConfiguration(config);

// 获取设备网关提供商
Optional<DeviceGatewayProvider> providerOpt = gatewayManager.getProvider("my-device-gateway");
if (providerOpt.isPresent()) {
    DeviceGatewayProvider provider = providerOpt.get();
    
    // 创建设备网关
    DeviceGateway gateway = provider.createDeviceGateway(properties);
    
    // 设置消息监听器
    gateway.setMessageListener(message -> {
        System.out.println("收到消息: " + message);
    });
    
    // 启动设备网关
    gateway.startup();
    
    // 使用设备网关
    System.out.println("设备网关状态: " + gateway.getState());
    
    // 关闭设备网关
    gateway.shutdown();
}
```

## 总结

通过以上开发文档，您可以使用 Java 实现与 JetLinks 项目兼容的网络组件和设备网关模块，而无需使用异步编程。在开发过程中，需要注意以下几点：

1. 使用传统的 Java 同步编程模式，替代原有的反应式编程模式
2. 保持接口设计的一致性，确保与原有系统的接口定义兼容
3. 实现必要的生命周期管理方法，如启动、暂停、关闭等
4. 注册到相应的管理器中，以便被系统发现和管理
5. 注意线程安全问题，尤其是在处理状态变更和消息监听时

JetLinks 的网络组件和设备网关模块设计合理，即使不使用异步编程，也可以通过适当的封装和转换，保持与原系统的互操作性。 