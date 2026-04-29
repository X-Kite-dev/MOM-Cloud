package com.kitex.mom.common.message.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ArrayUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigFieldEntity;
import com.kitex.mom.common.message.mapper.MessageConfigFieldMapper;
import com.kitex.mom.common.message.mapper.MessageConfigMapper;
import com.kitex.mom.common.message.service.MessageBusinessConfigService;
import com.kitex.mom.common.message.service.MessageConfigService;
import com.kitex.mom.common.message.vo.MessageConfigExcelVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 消息推送配置表
 *
 * @author kitex
 * @date 2025-01-14 08:44:24
 */
@Service
@RequiredArgsConstructor
public class MessageConfigServiceImpl extends ServiceImpl<MessageConfigMapper, MessageConfigEntity> implements MessageConfigService {

    private final MessageConfigFieldMapper messageConfigFieldMapper;

    private final MessageBusinessConfigService messageBusinessConfigService;

    private final RemoteDictService remoteDictService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(MessageConfigEntity messageConfig) {
        baseMapper.insert(messageConfig);
        for (MessageConfigFieldEntity messageConfigField : messageConfig.getMessageConfigFieldList()) {
            messageConfigField.setMessageId(messageConfig.getId());
            messageConfigFieldMapper.insert(messageConfigField);
        }

        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(MessageConfigEntity messageConfig) {
        baseMapper.updateById(messageConfig);
        for (MessageConfigFieldEntity messageConfigField : messageConfig.getMessageConfigFieldList()) {
            if (Objects.isNull(messageConfigField.getId())) {
                messageConfigField.setMessageId(messageConfig.getId());
                messageConfigFieldMapper.insert(messageConfigField);
            } else {
                messageConfigFieldMapper.updateById(messageConfigField);
            }
        }
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(Long[] ids) {
        baseMapper.deleteBatchIds(CollUtil.toList(ids));
        messageConfigFieldMapper.delete(Wrappers.<MessageConfigFieldEntity>lambdaQuery().in(MessageConfigFieldEntity::getMessageId, ids));
        return Boolean.TRUE;
    }

    /**
     * 导出excel 表格
     *
     * @param messageConfig 查询条件
     * @param ids           导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MessageConfigExcelVO> exportExcel(MessageConfigEntity messageConfig, Long[] ids) {
        Map<String, String> socialType = remoteDictService.getDictByType("message_social_type").getData().stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        return this.list(Wrappers.lambdaQuery(messageConfig).in(ArrayUtil.isNotEmpty(ids), MessageConfigEntity::getId, ids))
                .stream()
                .map(messageConfigEntity -> {
                    MessageConfigExcelVO messageConfigExcelVO = new MessageConfigExcelVO();
                    BeanUtil.copyProperties(messageConfigEntity, messageConfigExcelVO);
                    if (!socialType.isEmpty() && socialType.containsKey(messageConfigEntity.getType())) {
                        messageConfigExcelVO.setType(socialType.get(messageConfigEntity.getType()));
                    }
                    return messageConfigExcelVO;
                }).collect(Collectors.toList());
    }

    /**
     * 通过业务编码查询消息推送配置
     *
     * @param code 业务编码
     * @return R 对象
     */
    @Override
    public MessageConfigEntity getConfigByCode(String code) {
        MessageBusinessConfigEntity businessConfig = messageBusinessConfigService.getOne(new LambdaQueryWrapper<MessageBusinessConfigEntity>()
                .eq(MessageBusinessConfigEntity::getCode, code), false);
        if (businessConfig == null) {
            return null;
        }
        return this.getByIdDeep(businessConfig.getMessageConfigId());
    }
}
