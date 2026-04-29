package com.kitex.mom.mes.init;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.admin.api.entity.SysUserEntity;
import com.kitex.admin.api.feign.RemoteUserService;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.constant.enums.MessageTypeEnum;
import com.kitex.mom.common.message.dto.MessageDTO;
import com.kitex.mom.mes.entity.MesAndonNoticeEntity;
import com.kitex.mom.mes.entity.MesAndonRecordEntity;
import com.kitex.mom.mes.enums.AndonStateEnum;
import com.kitex.mom.mes.mapper.MesAndonRecordMapper;
import com.kitex.mom.mes.service.MesAndonNoticeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.DelayQueue;
import java.util.stream.Collectors;


@Component
@Slf4j
public class DelayedConfig {
    /**
     * 延时队列存储报文
     */
    public static final DelayQueue<MessageDelayed> delayQueue = new DelayQueue<>();
    @Autowired
    private MesAndonRecordMapper mesAndonRecordMapper;
    @Autowired
    private MesAndonNoticeService mesAndonNoticeService;
    @Autowired
    private RemoteMessagePushService messageService;
    @Autowired
    private RemoteUserService remoteUserService;

    public void connect() {
        // 创建线程去处理延时队列中的任务
        new Thread(() -> {
            try {
                while (true) {
                    // 从延时队列中获取任务
                    MessageDelayed messageDelayed = delayQueue.take();
                    MesAndonNoticeEntity notice = messageDelayed.getNotice();

                    MesAndonRecordEntity record = mesAndonRecordMapper.selectOne(new LambdaQueryWrapper<MesAndonRecordEntity>()
                            .eq(MesAndonRecordEntity::getCode, notice.getRecordCode())
                    );
                    if (ObjectUtil.isNotNull(record)) {
                        if (!AndonStateEnum.HANDLED.getCode().equals(record.getState()) && !AndonStateEnum.CLOSED.getCode().equals(record.getState()) && !AndonStateEnum.CANCEL.getCode().equals(record.getState())) {
                            if (AndonStateEnum.UN_RECEIVE.getCode().equals(record.getState())) {
                                mesAndonRecordMapper.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                                        .eq(MesAndonRecordEntity::getId, record.getId())
                                        .set(MesAndonRecordEntity::getState, AndonStateEnum.UP_UN_RECEIVE.getCode()));
                            } else if (AndonStateEnum.UN_HANDLE.getCode().equals(record.getState())) {
                                mesAndonRecordMapper.update(new LambdaUpdateWrapper<MesAndonRecordEntity>()
                                        .eq(MesAndonRecordEntity::getId, record.getId())
                                        .set(MesAndonRecordEntity::getState, AndonStateEnum.UP_UN_HANDLE.getCode()));
                            }

                            mesAndonNoticeService.save(notice);
                            // 钉钉通知相关人员未处理
                            String message = "安灯呼叫通知：\n" + "流程编号：" + record.getCode() + "\n" + "时间：" + timeStr() + "\n" + "异常类型：" + record.getTypeName() + "\n" + "车间：" + record.getWorkshopName() + "\n" + "产线：" + record.getLineName() + "\n" + "\n" + "超时未处理，请注意";
                            sendMessage(message, notice.getStaffCode());
                        }

                    }
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }).start();
    }

    public DelayQueue<MessageDelayed> getQueue() {
        return delayQueue;
    }

    public String timeStr() {
        // 获取当前时间
        LocalDateTime currentTime = LocalDateTime.now();

        // 定义时间格式化器
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // 格式化当前时间
        return currentTime.format(formatter);

    }

    private void sendMessage(String message, String users) {
        List<Long> userIdsList = Arrays.stream(users.split(",")).map(Long::parseLong).toList();
        List<SysUserEntity> userList = remoteUserService.getUserByIdInner(userIdsList.stream().toList()).getData();
        if (CollUtil.isEmpty(userList)) {
            throw new RuntimeException("查询用户数据失败！");
        }
        String userIds = userList.stream().map(SysUserEntity::getWxDingUserid).collect(Collectors.joining(","));
        //构建参数推送消息
        MessageDTO messageDTO = new MessageDTO();
        messageDTO.setCode("MES");
        messageDTO.setMessageType(MessageTypeEnum.TEXT.getType());
        messageDTO.setUserList(userIdsList);
        messageDTO.setUserIds(userIds);
        messageDTO.setMessage(message);
        messageService.pushPersonMsg(messageDTO);
    }
}