package com.kitex.mom.pms.task;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.pms.entity.PmsContractEntity;
import com.kitex.mom.pms.enums.PmsContractStatusEnum;
import com.kitex.mom.pms.service.PmsContractService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PmsContractTask {

    private final PmsContractService pmsContractService;

    private final RemoteMessagePushService remoteMessagePushService;

    @XxlJob("pms_contract_archivist")
    public void pmsContractArchivist() {
        log.info("开始执行合同归档常定时任务");

        List<PmsContractEntity> contractEntityList = pmsContractService.list(Wrappers.<PmsContractEntity>lambdaQuery()
                .eq(PmsContractEntity::getStatus, PmsContractStatusEnum.COMPLETE.getCode()));

        LocalDateTime now = LocalDateTime.now();

        List<PmsContractEntity> contractArchivistList = new ArrayList<>();

        Map<Long, Long> contractMap = new HashMap<>();
        for (PmsContractEntity contract : contractEntityList) {
            long daysBetween = ChronoUnit.DAYS.between(contract.getUpdateTime(), now);
            // 如果超过一周，则归档
            if (daysBetween >= 7) {
                contractMap.put(contract.getId(), daysBetween);
                contractArchivistList.add(contract);
            }
        }
        if (!contractArchivistList.isEmpty()) {
            // 将合同归档
            pmsContractService.update(new LambdaUpdateWrapper<PmsContractEntity>().in(PmsContractEntity::getId, contractMap.keySet())
                    .set(PmsContractEntity::getStatus, PmsContractStatusEnum.ARCHIVIST.getCode()));

            for (PmsContractEntity contract : contractArchivistList) {
                String message = "合同管理通知\n" +
                        "合同名称：" + contract.getPmsOrderNo() + "\n" +
                        "合同编号：" + contract.getContractNo() + "\n" +
                        "签约主体：" + contract.getContractManager() + "\n" +
                        "采购订单号：" + contract.getPmsOrderNo() + "\n" +
                        "客户名称：" + contract.getSupplierName() + "\n" +
                        "客户编码：" + contract.getSupplierCode() + "\n" + "\n" + "结算完成已有" + contractMap.get(contract.getId()) + "天，系统已自动归档，请注意";
                // 推送消息
                MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
                messageBusinessDTO.setCode("PMS");
                messageBusinessDTO.setMessage(message);
                remoteMessagePushService.businessPush(messageBusinessDTO);
            }
        }

        log.info("结束执行合同归档常定时任务");
    }
}