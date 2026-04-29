package com.kitex.mom.common.message.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.message.constant.GroupConstants;
import com.kitex.mom.common.message.dto.MessageBusinessConfigAddDTO;
import com.kitex.mom.common.message.dto.MessageBusinessConfigGroupAddDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.mapper.MessageBusinessConfigMapper;
import com.kitex.mom.common.message.service.MessageBusinessConfigService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 业务模块配置表
 *
 * @author kitex
 * @date 2025-01-15 09:17:26
 */
@Service
public class MessageBusinessConfigServiceImpl extends ServiceImpl<MessageBusinessConfigMapper, MessageBusinessConfigEntity> implements MessageBusinessConfigService {

    /**
     * 新增或修改业务模块配置表
     *
     * @param messageBusinessConfigAddDTO 业务模块配置DTO
     * @return R
     */
    @Override
    public Boolean saveOrUpdateBusinessConfig(MessageBusinessConfigAddDTO messageBusinessConfigAddDTO) {
        MessageBusinessConfigEntity messageBusinessConfig = new MessageBusinessConfigEntity();
        BeanUtils.copyProperties(messageBusinessConfigAddDTO, messageBusinessConfig);
        if (CollUtil.isNotEmpty(messageBusinessConfigAddDTO.getGroupList())) {
            JSONArray groupInformationArray = new JSONArray();
            for (MessageBusinessConfigGroupAddDTO clientDetailsDTO : messageBusinessConfigAddDTO.getGroupList()) {
                JSONObject informationObj = new JSONObject()
                        .set(GroupConstants.GROUP_HOOK, clientDetailsDTO.getGroupHook())
                        .set(GroupConstants.GROUP_SECRET, clientDetailsDTO.getGroupSecret());
                groupInformationArray.add(informationObj);
            }
            messageBusinessConfig.setGroupMessage(groupInformationArray.toString());
        }
        return this.saveOrUpdate(messageBusinessConfig);
    }

    /**
     * 通过id查询业务模块配置表
     *
     * @param id id
     * @return R
     */
    @Override
    public MessageBusinessConfigAddDTO getBusinessConfig(Long id) {
        MessageBusinessConfigAddDTO messageBusinessConfigAddDTO = new MessageBusinessConfigAddDTO();
        MessageBusinessConfigEntity messageBusinessConfig = this.getById(id);
        BeanUtils.copyProperties(messageBusinessConfig, messageBusinessConfigAddDTO);
        if (StrUtil.isNotBlank(messageBusinessConfig.getGroupMessage())) {
            List<MessageBusinessConfigGroupAddDTO> list = JSONUtil.toList(messageBusinessConfig.getGroupMessage(), MessageBusinessConfigGroupAddDTO.class);
            messageBusinessConfigAddDTO.setGroupList(list);
        } else {
            messageBusinessConfigAddDTO.setGroupList(CollUtil.newArrayList());
        }
        return messageBusinessConfigAddDTO;
    }
}