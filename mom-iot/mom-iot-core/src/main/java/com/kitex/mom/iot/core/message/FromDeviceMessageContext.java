package com.kitex.mom.iot.core.message;

import com.kitex.mom.iot.core.device.DeviceOperator;
import com.kitex.mom.iot.core.device.DeviceRegistry;
import com.kitex.mom.iot.core.message.codec.EncodedMessage;
import com.kitex.mom.iot.core.message.codec.MessageDecodeContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.function.Function;

public interface FromDeviceMessageContext extends MessageDecodeContext {


    static FromDeviceMessageContext of(EncodedMessage message,
                                       DeviceRegistry registry
    ) {
        return new FromDeviceMessageContext() {
            @Override
            public DeviceOperator getDevice(String deviceId) {
                return registry.getDevice(deviceId);
            }

            @Nonnull
            @Override
            public EncodedMessage getMessage() {
                return message;
            }
        };
    }

    static FromDeviceMessageContext of(EncodedMessage message,
                                       DeviceRegistry registry,
                                       Function<DeviceMessage, Void> handler
    ) {
        return new FromDeviceMessageContext() {
            @Override
            public DeviceOperator getDevice(String deviceId) {
                return registry.getDevice(deviceId);
            }

            @Nonnull
            @Override
            public EncodedMessage getMessage() {
                return message;
            }

            @Override
            public Void handleMessage(DeviceMessage message) {
                return handler.apply(message);
            }
        };
    }

    @Override
    @Nullable
    default DeviceOperator getDevice() {
        return null;
    }

}
