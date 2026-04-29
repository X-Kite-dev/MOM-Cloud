package com.kitex.mom.iot.constant;

public interface Constants {

    String Name = "mom-iot-system";

    /**
     * 功能类型
     */
    interface FuncType {
        /**
         * 属性
         */
        String PROPERTY = "property";

        /**
         * 服务
         */
        String SERVICE = "service";

        /**
         * 事件
         */
        String EVENT = "event";
    }

    /**
     * 数据类型
     */
    interface DataType {
        /**
         * 整数
         */
        String INT = "int";

        /**
         * 浮点数
         */
        String FLOAT = "float";

        /**
         * 字符串
         */
        String STRING = "string";

        /**
         * 布尔值
         */
        String BOOL = "bool";

        /**
         * 枚举
         */
        String ENUM = "enum";

        /**
         * 日期
         */
        String DATE = "date";

        /**
         * 结构体
         */
        String STRUCT = "struct";

        /**
         * 数组
         */
        String ARRAY = "array";
    }

    /**
     * MQTT相关常量
     */
    interface Mqtt {
        /**
         * 默认服务器地址
         */
        String DEFAULT_SERVER = "tcp://broker.emqx.io";

        /**
         * 默认端口
         */
        int DEFAULT_PORT = 1883;

        /**
         * 默认TLS端口
         */
        int DEFAULT_TLS_PORT = 8883;

        /**
         * 默认QoS
         */
        int DEFAULT_QOS = 1;

        /**
         * 默认连接超时时间(秒)
         */
        int DEFAULT_CONNECT_TIMEOUT = 30;

        /**
         * 默认心跳间隔(秒)
         */
        int DEFAULT_KEEP_ALIVE = 60;

        /**
         * 主题通配符
         */
        String TOPIC_WILDCARD = "#";

        /**
         * 设备上行数据主题模板
         */
        String DEVICE_UP_TOPIC_TEMPLATE = "/{productCode}/{deviceCode}/up";

        /**
         * 设备下行数据主题模板
         */
        String DEVICE_DOWN_TOPIC_TEMPLATE = "/{productCode}/{deviceCode}/down";
    }
} 