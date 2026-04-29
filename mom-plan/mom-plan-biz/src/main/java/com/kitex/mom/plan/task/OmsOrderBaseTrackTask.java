package com.kitex.mom.plan.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.oms.entity.OmsConfigEntity;
import com.kitex.mom.oms.entity.OmsOrderDetailsEntity;
import com.kitex.mom.oms.enums.OrderStateEnum;
import com.kitex.mom.plan.service.OmsConfigService;
import com.kitex.mom.plan.service.OmsOrderBaseTrackService;
import com.kitex.mom.plan.service.OmsOrderDetailsService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OmsOrderBaseTrackTask {

    private final OmsOrderDetailsService omsOrderDetailsService;

    private final OmsOrderBaseTrackService omsOrderBaseTrackService;

    private final RemoteMessagePushService messageService;

    private final OmsConfigService omsConfigService;

    private Integer issueDay;

    private LocalDate systemStartDate;

    @Bean
    public void omsOrderBaseTrackBean() {
        // 设置系统启动日期为当前日期
        systemStartDate = LocalDate.now();
    }

    /**
     * 每天执行一次
     * 订单下发定时任务
     */
    @XxlJob("oms_order_base_track")
    public void omsOrderBaseTrack() {
        OmsConfigEntity omsConfigEntity = omsConfigService.getOne(Wrappers.lambdaQuery(), false);
        if (omsConfigEntity != null && omsConfigEntity.getIssue()) {
            issueDay = omsConfigEntity.getIssueDay();
        } else {
            log.info("未获取订单配置信息，不执行定时任务");
            return;
        }

        // 获取当前日期
        LocalDate today = LocalDate.now();

        // 计算从系统启动到现在的天数
        long daysBetween = ChronoUnit.DAYS.between(systemStartDate, today);

        // 判断是否应该执行
        if (issueDay == 0) {
            // issueDay为0时直接执行
            log.info("issueDay=0，执行订单下发任务");
        } else if (daysBetween % (issueDay + 1) != 0) {
            log.info("根据issueDay={}的设置，今天不执行订单下发任务", issueDay);
            return;
        }

        log.info("订单下发定时任务 start==>");
        List<OmsOrderDetailsEntity> omsOrderDetailsList = omsOrderDetailsService.list(new LambdaQueryWrapper<OmsOrderDetailsEntity>()
                .eq(OmsOrderDetailsEntity::getState, OrderStateEnum.REVIEW.getCode()));
        for (OmsOrderDetailsEntity omsOrderDetails : omsOrderDetailsList) {
            // 订单下发
            omsOrderBaseTrackService.send(omsOrderDetails.getId());
            // 钉钉通知相关人员未处理
            String message = "订单通知\n" +
                    "订单编号：" + omsOrderDetails.getOrderCode() + "\n" +
                    "产品编码：" + omsOrderDetails.getProductCode() + "\n" +
                    "产品名称：" + omsOrderDetails.getProductName() + "\n" +
                    "规格型号：" + omsOrderDetails.getProductSpec() + "\n" +
                    "单位：" + omsOrderDetails.getProductUnit() + "\n" +
                    "交付时间：" + omsOrderDetails.getDeliverTime() + "\n" + "\n" + "到达下发时间，已下发计划和发货模块，请注意";
            sendMessage(message);
        }
        log.info("订单下发定时任务 ==>end");
    }

    private void sendMessage(String message) {
        //构建参数推送消息
        MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
        messageBusinessDTO.setCode("PLAN_OMS");
        messageBusinessDTO.setMessage(message);
        messageService.businessPush(messageBusinessDTO);
    }
}
