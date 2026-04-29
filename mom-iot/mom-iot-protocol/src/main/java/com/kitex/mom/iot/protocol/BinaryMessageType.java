package com.kitex.mom.iot.protocol;

import cn.hutool.core.map.MapUtil;
import com.kitex.mom.iot.core.message.DeviceMessage;
import com.kitex.mom.iot.core.message.property.ReportPropertyMessage;
import com.kitex.mom.iot.protocol.binary.BinaryMessage;
import com.kitex.mom.iot.protocol.binary.BinaryReportPropertyMessage;
import com.kitex.mom.iot.protocol.binary.DataType;
import io.netty.buffer.ByteBuf;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;


public enum BinaryMessageType {


    // 上报属性
    reportProperty(ReportPropertyMessage.class, BinaryReportPropertyMessage::new);


    private static final BinaryMessageType[] VALUES = values();
    private final Class<? extends DeviceMessage> forDevice;
    private final Supplier<BinaryMessage<DeviceMessage>> forTcp;


    BinaryMessageType(Class<? extends DeviceMessage> forDevice, Supplier<? extends BinaryMessage<?>> forTcp) {
        this.forDevice = forDevice;
        this.forTcp = (Supplier) forTcp;
    }


    /**
     * 读取二进制报文
     *
     * @param data
     * @param deviceIdMaybe
     * @return
     */
    public static DeviceMessage read(ByteBuf data, String deviceIdMaybe) {
        return read(data, deviceIdMaybe, (message, msgId) -> message);
    }

    /**
     * 读取二进制报文
     *
     * @param data
     * @param deviceIdMaybe
     * @param handler
     * @param <T>
     * @return
     */
    public static <T> T read(ByteBuf data,
                             String deviceIdMaybe,
                             BiFunction<DeviceMessage, Integer, T> handler) {
        /**
         * 招到处理本消息的属性
         * 解码消息内容
         * 最后吧内容写到对应的数据中
         */

        // 起始位
        data.readByte();

        // 状态A 可获取小数点的位置  001 = xxxxx0 010 = xxxxxx  011 = xxxxx.x 100 = xxxx.xx 101 = xxx.xxx
        byte statusA = data.readByte();
        // 设置 进位 数组 001 = xxxxx0 010 = xxxxxx  011 = xxxxx.x 100 = xxxx.xx 101 = xxx.xxx
        double[] carry = new double[]{0.001, 10, 1, 0.1, 0.01};
        double x = carry[statusA % 5];


        // 状态B
        byte statusB = data.readByte();
        // 状态c
        data.readByte();


        // 毛重 6 个字节  正常称重状态下，皮重为零时为输出毛重值，皮重不为零时为输出净重值。
        Double read = Double.parseDouble(DataType.STRING.read(data, 6).toString());
        // 读 6 位 为皮重  正常称重状态下为皮重值。
        Double read1 = Double.parseDouble(DataType.STRING.read(data, 6).toString());

        Map<String, Object> build = MapUtil.<String, Object>builder()
                .put("皮重", read * x)
                .put("毛重", read1 * x)
                .build();


        ReportPropertyMessage reportPropertyMessage = new ReportPropertyMessage();
        reportPropertyMessage.setDeviceId(deviceIdMaybe);
        reportPropertyMessage.setProperties(build);


        return handler.apply(reportPropertyMessage, 0);
    }

    public static DeviceMessage read(ByteBuf payload) {
        return read(payload, null);
    }
}
