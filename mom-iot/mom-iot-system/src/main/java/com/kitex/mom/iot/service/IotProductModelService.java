package com.kitex.mom.iot.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.iot.dto.IotProductModelDTO;
import com.kitex.mom.iot.entity.IotProductModelEntity;

import java.util.List;

/**
 * IoT产品数据模型服务接口
 *
 * @author system
 * @date 2025-03-12
 */
public interface IotProductModelService extends IService<IotProductModelEntity> {

    /**
     * 分页查询产品数据模型列表
     *
     * @param page  分页参数
     * @param model 查询条件
     * @return 分页结果
     */
    IPage<IotProductModelEntity> getModelPage(Page<IotProductModelEntity> page, IotProductModelEntity model);

    /**
     * 根据产品ID查询数据模型列表
     *
     * @param productId 产品ID
     * @return 数据模型列表
     */
    List<IotProductModelEntity> getModelsByProductId(Long productId);

    /**
     * 创建产品数据模型
     *
     * @param modelDTO 数据模型信息
     * @return 是否成功
     */
    boolean createModel(IotProductModelDTO modelDTO);

    /**
     * 批量创建产品数据模型
     *
     * @param modelDTOList 数据模型信息列表
     * @return 是否成功
     */
    boolean batchCreateModel(List<IotProductModelDTO> modelDTOList);

    /**
     * 更新产品数据模型
     *
     * @param modelDTO 数据模型信息
     * @return 是否成功
     */
    boolean updateModel(IotProductModelDTO modelDTO);

    /**
     * 删除产品数据模型
     *
     * @param id 数据模型ID
     * @return 是否成功
     */
    boolean removeModel(Long id);

    /**
     * 根据产品ID删除所有数据模型
     *
     * @param productId 产品ID
     * @return 是否成功
     */
    boolean removeByProductId(Long productId);
} 