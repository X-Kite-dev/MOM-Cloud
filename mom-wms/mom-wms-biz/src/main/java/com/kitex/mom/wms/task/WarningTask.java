package com.kitex.mom.wms.task;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.message.api.feign.RemoteMessagePushService;
import com.kitex.mom.common.message.dto.MessageBusinessDTO;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsLiveStockEntity;
import com.kitex.mom.wms.entity.WmsMaterialWarningEntity;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.impl.WmsMaterialWarningRuntimeService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class WarningTask {
    private final RemoteMaterialService remoteMaterialsService;
    private final RemoteMessagePushService remoteMessagePushService;
    private final WmsLiveStockService stocks;
    private final WmsMaterialWarningRuntimeService runtime;
    private final ObjectMapper objectMapper;

    /**
     * 一个任务对应一个真实租户工厂，参数例：{"tenantId":1,"factoryCode":"F1"}。
     */
    @XxlJob("material_warning")
    public void materialWarning() throws Exception {
        String parameter = XxlJobHelper.getJobParam();
        if (StrUtil.isBlank(parameter)) {
            throw new CheckedException("物料预警任务必须配置tenantId和factoryCode");
        }
        var scope = objectMapper.readTree(parameter);
        if (!scope.isObject() || !scope.path("tenantId").isIntegralNumber()
                || !scope.path("tenantId").canConvertToLong() || !scope.path("factoryCode").isTextual()) {
            throw new CheckedException("物料预警任务scope参数无效");
        }
        var notification = scope.get("notificationMode");
        if (notification != null && !notification.isTextual()) {
            throw new CheckedException("notificationMode必须是文本");
        }
        runScope(scope.get("tenantId").longValue(), scope.get("factoryCode").textValue(),
                notification == null ? "RECORD_ONLY" : notification.textValue());
    }

    public void runScope(Long tenant, String factory) {
        runScope(tenant, factory, "RECORD_ONLY");
    }

    public void runScope(Long tenant, String factory, String notificationMode) {
        if (!"RECORD_ONLY".equals(notificationMode) && !"TENANT_BUSINESS".equals(notificationMode)) {
            throw new CheckedException("notificationMode只支持RECORD_ONLY或TENANT_BUSINESS");
        }
        if (tenant == null || tenant <= 0 || StrUtil.isBlank(factory)) {
            throw new CheckedException("物料预警任务必须明确租户和工厂");
        }
        Long previousTenant = TenantContextHolder.getTenantId();
        String previousFactory = FactoryContextHolder.getFactoryCode();
        if (TenantContextHolder.getTenantSkip() || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("物料预警任务禁止跳过租户工厂隔离");
        }
        try {
            TenantContextHolder.setTenantId(tenant);
            FactoryContextHolder.setFactoryCode(factory);
            scan(tenant, factory, "TENANT_BUSINESS".equals(notificationMode));
        } finally {
            TenantContextHolder.setTenantId(previousTenant);
            FactoryContextHolder.setFactoryCode(previousFactory);
        }
    }

    private void scan(Long tenant, String factory, boolean notifyTenant) {
        R<List<MdmMaterialWarningConfigDTO>> response = remoteMaterialsService.warningConfigurations(tenant, factory);
        if (response == null || !response.isOk() || response.getData() == null) {
            throw new CheckedException("获取当前租户工厂的物料预警参数失败");
        }
        Map<String, MdmMaterialWarningConfigDTO> configurations = new HashMap<>();
        for (MdmMaterialWarningConfigDTO configuration : response.getData()) {
            if (configuration == null || !Objects.equals(configuration.getTenantId(), tenant)
                    || !Objects.equals(configuration.getFactoryCode(), factory)
                    || StrUtil.isBlank(configuration.getMaterialCode())
                    || configurations.putIfAbsent(configuration.getMaterialCode(), configuration) != null) {
                throw new CheckedException("物料预警参数范围不一致或物料编码重复，停止本次任务");
            }
        }
        long after = 0, visited = 0, created = 0, failures = 0;
        while (true) {
            List<WmsLiveStockEntity> page = stocks.list(new LambdaQueryWrapper<WmsLiveStockEntity>()
                    .select(WmsLiveStockEntity::getId, WmsLiveStockEntity::getTenantId,
                            WmsLiveStockEntity::getFactoryCode, WmsLiveStockEntity::getMatCode)
                    .eq(WmsLiveStockEntity::getTenantId, tenant).eq(WmsLiveStockEntity::getFactoryCode, factory)
                    .gt(WmsLiveStockEntity::getId, after).gt(WmsLiveStockEntity::getMatNum, 0)
                    .and(w -> w.eq(WmsLiveStockEntity::getExpirationStatus, false).or()
                            .isNull(WmsLiveStockEntity::getExpirationStatus)
                            .or().eq(WmsLiveStockEntity::getSlowMaterialStatus, false).or()
                            .isNull(WmsLiveStockEntity::getSlowMaterialStatus))
                    .orderByAsc(WmsLiveStockEntity::getId).last("LIMIT 200"));
            if (page.isEmpty()) {
                break;
            }
            for (WmsLiveStockEntity candidate : page) {
                if (candidate.getId() == null || candidate.getId() <= after
                        || !Objects.equals(candidate.getTenantId(), tenant)
                        || !Objects.equals(candidate.getFactoryCode(), factory)) {
                    throw new CheckedException("预警库存分页或作用域异常，停止本次任务");
                }
                after = candidate.getId();
                visited++;
                try {
                    MdmMaterialWarningConfigDTO configuration = configurations.get(candidate.getMatCode());
                    if (configuration == null) {
                        throw new CheckedException("库存没有当前租户工厂的物料预警参数");
                    }
                    List<WmsMaterialWarningEntity> warnings = runtime.evaluate(candidate.getId(), configuration);
                    created += warnings.size();
                    // REQUIRES_NEW方法已返回并提交。每条辅助通知失败不覆盖已提交的预警。
                    for (WmsMaterialWarningEntity warning : notifyTenant ? warnings
                            : List.<WmsMaterialWarningEntity>of()) {
                        try {
                            push(tenant, factory, warning);
                        } catch (Exception e) {
                            failures++;
                            log.error("预警记录{}已提交，辅助消息接收失败；可从预警页面查询，远程结果可能未知", warning.getBillNo(), e);
                            XxlJobHelper.log("预警{}已提交，租户级摘要提醒未确认接收，请核对消息中心记录", warning.getBillNo());
                        }
                    }
                } catch (Exception e) {
                    failures++;
                    log.error("库存{}物料{}预警执行失败，未确认新增预警；其他库存继续", candidate.getId(), candidate.getMatCode(), e);
                    XxlJobHelper.log("库存{}物料{}预警执行失败：{}", candidate.getId(), candidate.getMatCode(), e.getMessage());
                }
            }
            if (page.size() < 200) {
                break;
            }
        }
        log.info("物料预警完成 tenant={} factory={} 扫描={} 新增={} 失败={}", tenant, factory, visited, created, failures);
        XxlJobHelper.log("物料预警 tenant={} factory={} 扫描={} 新增={} 失败={}", tenant, factory, visited, created, failures);
        if (failures > 0) {
            throw new CheckedException("物料预警存在" + failures + "项失败，查看任务日志及预警记录");
        }
    }

    private void push(Long tenant, String factory, WmsMaterialWarningEntity warning) {
        MessageBusinessDTO message = new MessageBusinessDTO();
        message.setCode("WMS");
        // 现有消息配置是租户级，不承诺工厂收件人隔离；明细必须回到有工厂权限的业务页面读取。
        message.setMessage(String.format("工厂%s新增库存预警%s，请在仓储物料预警页面查看。", factory, warning.getBillNo()));
        R<Boolean> result = remoteMessagePushService.businessPushScoped(tenant, factory, message);
        if (result == null || !result.isOk() || !Boolean.TRUE.equals(result.getData())) {
            throw new CheckedException("消息中心未确认接收预警");
        }
    }
}
