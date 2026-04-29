package com.kitex.mom.pms.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.entity.PmsWarningEntity;
import com.kitex.mom.pms.service.PmsPlanService;
import com.kitex.mom.pms.service.PmsWarningService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class PmsPlanWarningTask {

    private final PmsPlanService pmsPlanService;

    private final PmsWarningService pmsWarningService;

    private final RemoteMessagePushService remoteMessagePushService;

    private Map<String, PmsWarningEntity> pmsWarningMap = new HashMap<String, PmsWarningEntity>();

    @XxlJob("pms_plan_warning")
    public void pmsPlanWarning() {
        log.info("开始执行采购计划异常定时任务");

        if (pmsWarningMap.isEmpty()) {
            pmsWarningMap = pmsWarningService.list()
                    .stream()
                    .collect(Collectors.toMap(PmsWarningEntity::getPmsPlanNo, Function.identity(), (a, b) -> a));
        }
        Set<String> pmsPlanNos = pmsWarningMap.keySet();
        List<PmsPlanEntity> pmsPlanEntityList = pmsPlanService.list(new LambdaQueryWrapper<PmsPlanEntity>()
                .notIn(!pmsPlanNos.isEmpty(), PmsPlanEntity::getPmsPlanNo, pmsPlanNos));

        for (PmsPlanEntity pmsPlanEntity : pmsPlanEntityList) {
            // 延期交货处理逻辑
            this.delay(pmsPlanEntity);

            this.quality(pmsPlanEntity);
        }

        log.info("结束执行采购计划异常定时任务");
    }

    /*
     * 延期交货处理
     */
    private void delay(PmsPlanEntity pmsPlanEntity) {
        Double planArrivalNum = Optional.ofNullable(pmsPlanEntity.getPlanArrivalNum()).orElse(0.0);
        if (LocalDate.now().isAfter(pmsPlanEntity.getPlanDeliveryTime()) &&
                !Objects.equals(planArrivalNum, pmsPlanEntity.getPlanOrderNum())) {
            PmsWarningEntity pmsWarningEntity = new PmsWarningEntity();
            BeanUtils.copyProperties(pmsPlanEntity, pmsWarningEntity);
            pmsWarningEntity.setType("0");
            double num = pmsPlanEntity.getPlanOrderNum() - planArrivalNum;
            String reason = String.format("到达交货日期缺少物料%s %s%s", pmsPlanEntity.getMatName(), num, pmsPlanEntity.getMatUnit());
            pmsWarningEntity.setReason(reason);
            pmsWarningService.save(pmsWarningEntity);
            // 推送消息
            MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
            messageBusinessDTO.setCode("PMS");
            messageBusinessDTO.setMessage(reason);
            remoteMessagePushService.businessPush(messageBusinessDTO);

            pmsWarningMap.put(pmsWarningEntity.getPmsPlanNo(), pmsWarningEntity);
        }
    }

    /*
     * 质检不合格处理
     */
    private void quality(PmsPlanEntity pmsPlanEntity) {
        if (pmsPlanEntity.getQuality() != null && !pmsPlanEntity.getQuality()) {
            PmsWarningEntity pmsWarningEntity = new PmsWarningEntity();
            BeanUtils.copyProperties(pmsPlanEntity, pmsWarningEntity);
            pmsWarningEntity.setType("1");
            pmsWarningEntity.setReason(pmsPlanEntity.getQualityReason());
            pmsWarningService.save(pmsWarningEntity);
            // 推送消息
            MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
            messageBusinessDTO.setCode("PMS");
            messageBusinessDTO.setMessage(pmsPlanEntity.getQualityReason());
            remoteMessagePushService.businessPush(messageBusinessDTO);

            pmsWarningMap.put(pmsWarningEntity.getPmsPlanNo(), pmsWarningEntity);
        }
    }

}
