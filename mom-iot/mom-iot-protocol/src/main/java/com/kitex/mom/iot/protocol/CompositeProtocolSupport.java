package com.kitex.mom.iot.protocol;

import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.protocol.ProtocolSupport;
import com.kitex.mom.iot.core.protocol.TransportType;
import com.kitex.mom.iot.core.route.MqttRoute;
import com.kitex.mom.iot.core.route.Route;
import lombok.Getter;
import lombok.Setter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 协议抽象实现
 */
@Getter
@Setter
public class CompositeProtocolSupport implements ProtocolSupport {

    /**
     * 协议支持的消息编解码器
     */
    private final Map<String, DeviceMessageCodec> messageCodecSupports = new ConcurrentHashMap<>();
    /**
     * 协议ID
     */
    private String id;
    /**
     * 协议名称
     */
    private String name;
    /**
     * 协议描述
     */
    private String description;
    /**
     * 协议对应的路由
     */
    private Map<String, List<Route>> routesMap = new HashMap<>();


    /**
     * 协议对应的文件
     */
    private Map<String, Supplier<String>> docFiles = new ConcurrentHashMap<>();


    @Override
    public List<Route> getRoutes(TransportType transport) {
        return routesMap.computeIfAbsent(transport.name(), id -> new ArrayList<>());
    }

    @Override
    public DeviceMessageCodec getCodec(TransportType transport) {
        return messageCodecSupports.computeIfAbsent(transport.name(), id -> null);
    }

    @Override
    public List<String> getSupports() {
        // 从路由中获取
        return routesMap.keySet().stream().toList();
    }

    /**
     * 增加路由信息
     *
     * @param transportType
     * @param collect
     */
    public void addRoutes(TransportType transportType, List<MqttRoute> collect) {
        routesMap.computeIfAbsent(transportType.name(), id -> new ArrayList<>()).addAll(collect);
    }

    /**
     * 设置文档
     *
     * @param transportType
     * @param documentUrlOrFile
     * @param classLoader
     */
    public void setDocument(TransportType transportType, String documentUrlOrFile, ClassLoader classLoader) {
        if (documentUrlOrFile.startsWith("http")) {
            setDocument(transportType, () -> documentUrlOrFile);
        } else {
            setDocument(transportType, () -> new ClassPathResource(documentUrlOrFile, classLoader));
        }
    }

    /**
     * 设置文档
     *
     * @param transport
     * @param document
     */
    public void setDocument(TransportType transport, Supplier<String> document) {

        docFiles.put(transport.name(), document);
    }

    /**
     * 设置文档
     *
     * @param transport
     * @param document
     */
    public void setDocument(TransportType transport, Callable<Resource> document) {
        setDocument(transport, () -> {
            try {
                Resource resource = document.call();
                if (resource.exists()) {
                    try (InputStream input = resource.getInputStream()) {
                        return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
                    }
                }
            } catch (Throwable ignore) {
            }
            return null;
        });
    }


    /**
     * 设置编解码器
     *
     * @param codec
     */
    public void addMessageCodecSupport(DeviceMessageCodec codec) {
        addMessageCodecSupport(codec.getSupportTransport(), codec);
    }

    /**
     * 设置编解码器
     *
     * @param supportTransport
     * @param codec
     */
    private void addMessageCodecSupport(TransportType supportTransport, DeviceMessageCodec codec) {
        messageCodecSupports.put(supportTransport.name(), codec);
    }


    @Override
    public String getDocument(TransportType transport) {
        Supplier<String> document = docFiles.get(transport.name());
        if (document == null) {
            return null;
        }
        return document.get();
    }
}