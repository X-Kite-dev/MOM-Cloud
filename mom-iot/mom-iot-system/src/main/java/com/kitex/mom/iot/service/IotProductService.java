package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotProductDTO;
import com.kitex.mom.iot.entity.IotProductEntity;

/**
 * IoT产品服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotProductService extends IService<IotProductEntity> {

    /**
     * 分页查询产品列表
     *
     * @param page    分页参数
     * @param product 查询条件
     * @return 分页结果
     */
    IPage<IotProductEntity> getProductPage(Page<IotProductEntity> page, IotProductEntity product);

    /**
     * 创建产品
     *
     * @param productDTO 产品信息
     * @return 是否成功
     */
    boolean createProduct(IotProductDTO productDTO);

    /**
     * 更新产品
     *
     * @param productDTO 产品信息
     * @return 是否成功
     */
    boolean updateProduct(IotProductDTO productDTO);

    /**
     * 删除产品
     *
     * @param id 产品ID
     * @return 是否成功
     */
    boolean removeProduct(Long id);
} 