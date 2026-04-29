package com.kitex.mom.tpm.delayedConfig;


import cn.hutool.core.collection.CollUtil;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.tpm.entity.TpmRepairTaskEntity;
import com.kitex.mom.tpm.enums.RepairStateEnum;
import com.kitex.mom.tpm.mapper.TpmRepairTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TpmDelayedConfig {

    public static final String TPMDELAY = "TPMDELAY";

    public static final String MESSAGE = "维修任务编码为【%s】的维修任务已超出规定完成时间!    ";
    /**
     * 延时队列存储报文
     */
    public static final DelayQueue<TpmMessageDelayed> delayQueue = new DelayQueue<>();
    @Autowired
    private TpmRepairTaskMapper tpmRepairTaskMapper;
    @Autowired
    private RemoteMessagePushService remoteMessagePushService;
    @Autowired
    private RemoteUserService remoteUserService;

    public void connect() {
        // 创建线程去处理延时队列中的任务
        new Thread(() -> {
            try {
                while (true) {
                    // 从延时队列中获取任务
                    TpmMessageDelayed messageDelayed = delayQueue.take();
                    TpmTaskNotice notice = messageDelayed.getNotice();
                    //获取设备任务
                    TpmRepairTaskEntity repairTask = tpmRepairTaskMapper.selectById(notice.getId());
                    //如果状态未未完成，发送消息
                    if (RepairStateEnum.CREATE.getCode().equals(repairTask.getState())) {
                        //获取发送对象
                        List<Long> userIdList = notice.getUserIdList();
                        List<SysUserEntity> userList = remoteUserService.getUserByIdInner(userIdList).getData();
                        String userIds = null;
                        if (CollUtil.isNotEmpty(userList)) {
                            userIds = userList.stream().map(SysUserEntity::getWxDingUserid).collect(Collectors.joining(","));
                        }
                        //构建消息体
                        String message = String.format(MESSAGE, repairTask.getTaskCode());
                        //构建参数推送消息
                        MessageDTO messageDTO = new MessageDTO();
                        messageDTO.setCode(TPMDELAY);
                        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
                        messageDTO.setUserList(userIdList);
                        messageDTO.setUserIds(userIds);
                        messageDTO.setMessage(message);
                        remoteMessagePushService.pushPersonMsg(messageDTO);
                    }

                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    public DelayQueue<TpmMessageDelayed> getQueue() {
        return delayQueue;
    }

}
