package com.kitex.mom.common.message.service.impl;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.admin.api.entity.SysDictItemEntity;
import com.kitex.admin.api.feign.RemoteDictService;
import com.kitex.admin.api.feign.RemoteMessageService;
import com.kitex.admin.api.vo.OrgTreeVO;
import com.kitex.admin.api.vo.SysMessageVO;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.common.message.entity.MessageBusinessConfigEntity;
import com.kitex.mom.common.message.entity.MessageConfigEntity;
import com.kitex.mom.common.message.entity.MessageLogEntity;
import com.kitex.mom.common.message.handler.business.MessageBusinessHandler;
import com.kitex.mom.common.message.mapper.MessageLogMapper;
import com.kitex.mom.common.message.service.MessageBusinessConfigService;
import com.kitex.mom.common.message.service.MessageConfigService;
import com.kitex.mom.common.message.service.MessageLogService;
import com.kitex.mom.common.message.vo.MessageLogExcelVO;
import com.kitex.mom.mdm.enums.YesOrNoStateEnum;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 消息推送记录
 *
 * @author kitex
 * @date 2025-01-13 16:29:19
 */
@Service
@AllArgsConstructor
public class MessageLogServiceImpl extends ServiceImpl<MessageLogMapper, MessageLogEntity> implements MessageLogService {

    private final RemoteDictService remoteDictService;

    private final Map<String, MessageBusinessHandler> messageBusinessHandlerMap;

    private final MessageBusinessConfigService messageBusinessConfigService;

    private final MessageConfigService messageConfigService;

    private final RemoteMessageService remoteMessageService;

    /**
     * 导出excel 表格
     *
     * @param messageLog 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    @Override
    public List<MessageLogExcelVO> export(MessageLogEntity messageLog, Long[] ids) {
        Map<String, String> socialType = remoteDictService.getDictByType("message_social_type").getData().stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        Map<String, String> businessType = remoteDictService.getDictByType("modules").getData().stream()
                .collect(Collectors.toMap(SysDictItemEntity::getItemValue, SysDictItemEntity::getLabel, (a, b) -> a));
        return this.list(Wrappers.lambdaQuery(messageLog).in(ArrayUtil.isNotEmpty(ids), MessageLogEntity::getId, ids))
                .stream().map(workCenter -> {
                    MessageLogExcelVO mdmWorkCenterExcelVO = new MessageLogExcelVO();
                    BeanUtils.copyProperties(workCenter, mdmWorkCenterExcelVO);
                    if (!socialType.isEmpty() && socialType.containsKey(workCenter.getType())) {
                        mdmWorkCenterExcelVO.setType(socialType.get(workCenter.getType()));
                    }
                    if (!businessType.isEmpty() && businessType.containsKey(workCenter.getModuleCode())) {
                        mdmWorkCenterExcelVO.setModuleCode(socialType.get(workCenter.getModuleCode()));
                    }
                    mdmWorkCenterExcelVO.setResult(workCenter.getResult() ? "成功" : "失败");
                    return mdmWorkCenterExcelVO;
                }).collect(Collectors.toList());
    }

    /**
     * 消息重推
     *
     * @param id id
     */
    @Override
    public Boolean businessPush(Long id) {
        MessageLogEntity messageLog = this.getById(id);
        // 获取消息配置信息
        MessageBusinessConfigEntity messageBusinessConfig = messageBusinessConfigService.getOne(new LambdaQueryWrapper<MessageBusinessConfigEntity>()
                .eq(MessageBusinessConfigEntity::getCode, messageLog.getBusinessType()), false);
        if (messageBusinessConfig == null) {
            throw new CheckedException("业务消息配置不存在");
        }
        if (messageBusinessConfig.getPersonalStatus() && StrUtil.isBlank(messageLog.getPushUser())) {
            throw new CheckedException("推送失败，缺少推送用户");
        }
        MessageConfigEntity messageConfig = messageConfigService.getByIdDeep(messageBusinessConfig.getMessageConfigId());
        // 组装DTO
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setMessageBusinessConfig(messageBusinessConfig);
        messageDTO.setMessageConfig(messageConfig);
        messageDTO.setUserIds(messageLog.getPushUser());
        messageDTO.setMessage(messageLog.getContent());
        messageDTO.setMessageType(messageLog.getMessageType());
        // 根据业务类型获取对应的handler
        Boolean bussinessPushStatus = messageBusinessHandlerMap.get(messageLog.getBusinessType()).businessPush(messageDTO);

        // 推送站内信
        String[] userIds = messageLog.getUserIds();
        List<Long> userIdList = Arrays.stream(userIds)
                .map(Long::valueOf) // 将每个字符串转换为 Long
                .collect(Collectors.toList());
        Boolean systemMsgStatus = sendSystemMsg(messageLog.getTitle(), messageLog.getContent(), userIdList);
        return bussinessPushStatus && systemMsgStatus;
    }

    /**
     * 推送站内信
     *
     * @param title    标题
     * @param msg      内容
     * @param userList 用户id列表
     */
    private Boolean sendSystemMsg(String title, String msg, List<Long> userList) {
        // 推送站内信
        SysMessageVO sysMessageVO = new SysMessageVO();
        sysMessageVO.setAllFlag("0");
        sysMessageVO.setCategory("1");
        sysMessageVO.setContent(msg);
        sysMessageVO.setSort(0);
        sysMessageVO.setSendFlag(YesOrNoStateEnum.YES.getCode());
        sysMessageVO.setTitle(title);
        List<OrgTreeVO> OrgTreeVOList = new ArrayList<>();
        for (Long userId : userList) {
            OrgTreeVO orgTreeVO = new OrgTreeVO();
            orgTreeVO.setId(userId);
            OrgTreeVOList.add(orgTreeVO);
        }
        sysMessageVO.setUserList(OrgTreeVOList);
        sysMessageVO.setCreateBy("admin");
        sysMessageVO.setCreateTime(LocalDateTime.now());
        R r = remoteMessageService.senMsg(sysMessageVO);
        if (r.getCode() != 0) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }
}