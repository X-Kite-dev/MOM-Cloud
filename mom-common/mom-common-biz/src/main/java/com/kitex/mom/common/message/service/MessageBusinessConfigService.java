package com.kitex.mom.common.message.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.common.message.dto.MessageBusinessConfigAddDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;

public interface MessageBusinessConfigService extends IService<MessageBusinessConfigEntity> {

    /**
     * 新增或修改业务模块配置表
     *
     * @param messageBusinessConfigAddDTO 业务模块配置DTO
     * @return R
     */
    Boolean saveOrUpdateBusinessConfig(MessageBusinessConfigAddDTO messageBusinessConfigAddDTO);

    /**
     * 通过id查询业务模块配置表
     *
     * @param id id
     * @return R
     */
    MessageBusinessConfigAddDTO getBusinessConfig(Long id);
}