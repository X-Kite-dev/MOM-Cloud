package com.kitex.mom.wms.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.RetOps;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsMaterialWarningService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarningTask {

    private final RemoteMaterialService remoteMaterialsService;

    private final RemoteSeqConfigService remoteSeqConfigService;

    private final RemoteMessagePushService remoteMessagePushService;

    private final WmsLiveStockService wmsLiveStockService;

    private final WmsMaterialWarningService wmsMaterialWarningService;

    private Map<String, MdmMaterialEntity> materialMap = new HashMap<String, MdmMaterialEntity>();

    @XxlJob("material_warning")
    public void materialWarning() {
        log.info("开始执行物料预警定时任务");
        // 获取物料列表
        if (materialMap.isEmpty()) {
            materialMap = RetOps.of(remoteMaterialsService.getAllList()).getData()
                    .orElseThrow(() -> new CheckedException("获取物料信息失败"))
                    .stream()
                    .collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (a, b) -> a));
        }
        // 获取实时库存
        List<WmsLiveStockEntity> wmsLiveStockEntityList = wmsLiveStockService.list(new LambdaQueryWrapper<WmsLiveStockEntity>()
                .eq(WmsLiveStockEntity::getExpirationStatus, false).or().eq(WmsLiveStockEntity::getSlowMaterialStatus, false));
        for (WmsLiveStockEntity wmsLiveStockEntity : wmsLiveStockEntityList) {
            MdmMaterialEntity material = materialMap.get(wmsLiveStockEntity.getMatCode());
            if (material == null) {
                material = remoteMaterialsService.detailsByNoToken(wmsLiveStockEntity.getMatCode()).getData();
                materialMap.put(wmsLiveStockEntity.getMatCode(), material);
            }
            // 有效期处理逻辑
            Boolean expiration = false;
            if (material.getExpiration()) {
                expiration = expiration(wmsLiveStockEntity, material);
            }

            // 呆滞物料处理逻辑
            Boolean aBoolean = false;
            if (material.getSlowMaterial()) {
                aBoolean = slowMaterial(wmsLiveStockEntity, material);
            }

            if (expiration || aBoolean) {
                wmsLiveStockService.updateById(wmsLiveStockEntity);
            }
        }
        log.info("结束执行物料预警定时任务");
    }

    /*
     * 有效期处理逻辑
     */
    private Boolean expiration(WmsLiveStockEntity wmsLiveStockEntity, MdmMaterialEntity material) {
        LocalDate localDate = wmsLiveStockEntity.getMatProduceDate().plusDays(material.getExpirationDay());
        if (localDate.isBefore(LocalDate.now()) && !wmsLiveStockEntity.getExpirationStatus()) {
            WmsMaterialWarningEntity warning = new WmsMaterialWarningEntity();
            BeanUtils.copyProperties(wmsLiveStockEntity, warning);
            warning.setId(null);
            warning.setBillNo(remoteSeqConfigService.create("material_warning").getData());
            warning.setCreateTime(LocalDateTime.now());
            warning.setType("0");
            warning.setWarningDays(material.getExpirationDay());
            wmsMaterialWarningService.save(warning);

            // 推送消息
            String message = String.format("仓库%s库位%s物料%s已过期%s天", warning.getWarehouseName(), warning.getSiteName(),
                    material.getMaterialName(), LocalDate.now().getDayOfYear() - localDate.getDayOfYear());
            MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
            messageBusinessDTO.setCode("WMS");
            messageBusinessDTO.setMessage(message);
            remoteMessagePushService.businessPush(messageBusinessDTO);

            wmsLiveStockEntity.setExpirationStatus(true);
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    /*
     * 呆滞物料处理逻辑
     */
    private Boolean slowMaterial(WmsLiveStockEntity wmsLiveStockEntity, MdmMaterialEntity material) {
        LocalDateTime localDateTime = wmsLiveStockEntity.getInTime().plusDays(material.getSlowMaterialDay());
        if (localDateTime.isBefore(LocalDateTime.now()) && !wmsLiveStockEntity.getSlowMaterialStatus()) {
            WmsMaterialWarningEntity warning = new WmsMaterialWarningEntity();
            BeanUtils.copyProperties(wmsLiveStockEntity, warning);
            warning.setId(null);
            warning.setBillNo(remoteSeqConfigService.create("material_warning").getData());
            warning.setCreateTime(LocalDateTime.now());
            warning.setType("1");
            warning.setWarningDays(material.getSlowMaterialDay());
            wmsMaterialWarningService.save(warning);

            // 推送消息
            String message = String.format("仓库%s库位%s物料%s已超过%s天", warning.getWarehouseName(), warning.getSiteName(),
                    material.getMaterialName(), LocalDateTime.now().getDayOfYear() - localDateTime.getDayOfYear());
            MessageBusinessDTO messageBusinessDTO = new MessageBusinessDTO();
            messageBusinessDTO.setCode("WMS");
            messageBusinessDTO.setMessage(message);
            remoteMessagePushService.businessPush(messageBusinessDTO);

            wmsLiveStockEntity.setSlowMaterialStatus(true);
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

}
