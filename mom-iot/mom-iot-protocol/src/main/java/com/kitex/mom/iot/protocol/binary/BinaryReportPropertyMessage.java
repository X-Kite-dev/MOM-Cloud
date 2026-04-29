package com.kitex.mom.iot.protocol.binary;

import cn.hutool.core.map.MapUtil;
import com.kitex.mom.iot.core.message.property.ReportPropertyMessage;
import com.kitex.mom.iot.protocol.BinaryMessageType;
import io.netty.buffer.ByteBuf;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
public class BinaryReportPropertyMessage implements BinaryMessage<ReportPropertyMessage> {


    private ReportPropertyMessage message;

    /**
     * 消息类型
     *
     * @return
     */
    @Override
    public BinaryMessageType getType() {
        return BinaryMessageType.reportProperty;
    }

    /**
     * 读取消息
     *
     * @param buf
     */
    @Override
    public void read(ByteBuf buf) {
        message = new ReportPropertyMessage();
        // 读 6 位 毛重


//        Map<String, Object> map = (Map<String, Object>) DataType.OBJECT.read(buf);
        Map<String, Object> map = MapUtil.<String, Object>builder().build();
        message.setProperties(map);

    }

    @Override
    public void write(ByteBuf buf) {
//        DataType.OBJECT.write(buf, message.getProperties());

    }

    @Override
    public ReportPropertyMessage getMessage() {
        return message;
    }

    @Override
    public void setMessage(ReportPropertyMessage message) {
        this.message = message;

    }
}
