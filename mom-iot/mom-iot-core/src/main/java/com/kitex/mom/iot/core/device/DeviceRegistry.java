package com.kitex.mom.iot.core.device;


public interface DeviceRegistry {

    /**
     * 获取设备信息
     *
     * @param deviceId
     * @return
     */
    DeviceOperator getDevice(String deviceId);


    /**
     * 获取产品信息
     *
     * @param productId
     * @return
     */
    DeviceProductOperator getProduct(String productId);


    /**
     * 注册设备,并返回设备操作接口,请勿缓存返回值,注册中心已经实现本地缓存.
     *
     * @param deviceInfo 设备基础信息
     * @return 设备操作接口
     * @see DeviceRegistry#getDevice(String)
     */
    DeviceOperator register(DeviceInfo deviceInfo);

    /**
     * 注册产品(型号)信息
     *
     * @param productInfo 产品(型号)信息
     * @return 注册结果
     */
    DeviceProductOperator register(ProductInfo productInfo);

}
