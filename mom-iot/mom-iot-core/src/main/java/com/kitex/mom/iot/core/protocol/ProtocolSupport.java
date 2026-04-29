package com.kitex.mom.iot.core.protocol;


import com.kitex.mom.iot.core.codec.DeviceMessageCodec;
import com.kitex.mom.iot.core.route.Route;

import java.util.List;

/**
 * 物联网协议接口
 * 所有协议实现必须实现此接口
 */
public interface ProtocolSupport {

    /**
     * 获取协议ID
     *
     * @return 协议唯一标识符
     */
    String getId();

    /**
     * 获取协议名称
     *
     * @return 协议名称
     */
    String getName();

    /**
     * 获取协议描述
     *
     * @return 协议描述
     */
    String getDescription();


    /**
     * 获取指定协议的路由信息,比如MQTT topic,HTTP url地址
     */
    List<Route> getRoutes(TransportType transport);

    /**
     * 获取编解码
     */
    DeviceMessageCodec getCodec(TransportType transport);


    /**
     * 支持的协议
     */
    List<String> getSupports();


    /**
     * 获取协议文档
     *
     * @param transport
     * @return
     */
    default String getDocument(TransportType transport) {
        return null;
    }

}