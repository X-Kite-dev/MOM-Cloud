package com.kitex.mom.mes.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.enums.InOutEnum;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.mapper.MesMaterialStartCheckMapper;
import com.kitex.mom.mes.mapper.MesTaskReportMapper;
import com.kitex.mom.mes.mapper.MesWarehouseFeedMapper;
import com.kitex.mom.mes.mapper.MesWarehouseInoutMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/**
 * 同一派工开工后沿真实START_CHECK固定消耗模式；不改变BOM/路线/模板快照，逆向仍恢复原消耗事实。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialConsumptionModeResolver {

    private final MesActualFactoryAccessService access;
    private final RemoteParamService defaults;
    private final MesMaterialStartCheckMapper checks;
    private final MesWarehouseFeedMapper feeds;
    private final MesWarehouseInoutMapper flows;
    private final MesTaskReportMapper reports;

    private static long count(Long value) {
        require(value != null && value >= 0, "消耗历史不可核验，不能按没有来源处理");
        return value;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    /**
     * 实际写入调用者须已持有原父工单/派工锁，预览仅返回当前点时模式。
     */
    public Mode resolve(MesTaskArrangeEntity arrange) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(arrange != null && arrange.getId() != null && tenant != null && tenant > 0
                        && !Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                        && Objects.equals(arrange.getTenantId(), tenant) && Objects.equals(arrange.getFactoryCode(), factory)
                        && text(arrange.getCode()) && text(arrange.getTaskCode()) && text(arrange.getProduceTaskCode()),
                "消耗模式缺少真实租户工厂及派工归属");
        long feedCount = count(feeds.selectCount(new LambdaQueryWrapper<MesWarehouseFeedEntity>()
                .eq(MesWarehouseFeedEntity::getTenantId, tenant).eq(MesWarehouseFeedEntity::getFactoryCode, factory)
                .eq(MesWarehouseFeedEntity::getTaskCode, arrange.getTaskCode())
                .eq(MesWarehouseFeedEntity::getArrangeCode, arrange.getCode())));
        long flowCount = count(flows.selectCount(new LambdaQueryWrapper<MesWarehouseInoutEntity>()
                .eq(MesWarehouseInoutEntity::getTenantId, tenant).eq(MesWarehouseInoutEntity::getFactoryCode, factory)
                .eq(MesWarehouseInoutEntity::getBillNo, arrange.getCode())
                .eq(MesWarehouseInoutEntity::getBizType, WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode())
                .eq(MesWarehouseInoutEntity::getOperateType, InOutEnum.OUT.getCode())));
        require(feedCount == 0 || flowCount == 0, "原派工已有投料与倒冲混合历史，须受控核对，禁止重新选择消耗模式");
        Mode mode;
        if (arrange.getMaterialGateRecordId() != null) {
            mode = anchored(arrange);
        } else {
            long reportCount = count(reports.selectCount(new LambdaQueryWrapper<MesTaskReportEntity>()
                    .eq(MesTaskReportEntity::getTenantId, tenant).eq(MesTaskReportEntity::getFactoryCode, factory)
                    .eq(MesTaskReportEntity::getArrangeCode, arrange.getCode())));
            require(arrange.getStartTime() == null && Set.of("UN_RECEIVE", "RECEIVE").contains(Objects.toString(arrange.getStatus(), ""))
                            && (arrange.getCompleteNum() == null || arrange.getCompleteNum().signum() == 0)
                            && reportCount == 0 && flowCount == 0,
                    "已开工或已有生产来源的派工缺少原开工消耗模式锚点，须受控核对，不能按今日全局配置推断");
            var setting = defaults.getByKey("BACK_FLUSH");
            require(setting != null && setting.isOk() && Set.of("0", "1").contains(Objects.toString(setting.getData(), "")),
                    "首次开工的投料/倒冲配置不可核验");
            mode = "1".equals(setting.getData()) ? Mode.BACKFLUSH : Mode.FEED;
        }
        require(mode != Mode.BACKFLUSH || feedCount == 0, "原派工已有真实投料，不能改走倒冲再次扣减线边库存");
        require(mode != Mode.FEED || flowCount == 0, "原派工已有真实倒冲，不能改走投料再次耗用");
        return mode;
    }

    private Mode anchored(MesTaskArrangeEntity arrange) {
        var row = checks.selectOne(new LambdaQueryWrapper<MesMaterialStartCheckEntity>()
                .eq(MesMaterialStartCheckEntity::getId, arrange.getMaterialGateRecordId())
                .eq(MesMaterialStartCheckEntity::getTenantId, arrange.getTenantId())
                .eq(MesMaterialStartCheckEntity::getFactoryCode, arrange.getFactoryCode()));
        require(row != null && Objects.equals(row.getId(), arrange.getMaterialGateRecordId())
                && Objects.equals(row.getTenantId(), arrange.getTenantId()) && Objects.equals(row.getFactoryCode(), arrange.getFactoryCode())
                && Objects.equals(row.getArrangeId(), arrange.getId()) && Objects.equals(row.getArrangeCode(), arrange.getCode())
                && Objects.equals(row.getTaskCode(), arrange.getTaskCode()) && Objects.equals(row.getProduceTaskCode(), arrange.getProduceTaskCode())
                && "START_CHECK".equals(row.getStage()), "原开工消耗模式锚点缺失、身份不符或不是成功开工核验");
        boolean allowedStart = "READY".equals(row.getResult()) && Set.of("STRICT_REMAINING", "OBSERVE_REMAINING").contains(Objects.toString(row.getMode(), ""))
                || "SHORTAGE".equals(row.getResult()) && "OBSERVE_REMAINING".equals(row.getMode());
        require(allowedStart && text(row.getManifestJson()) && Objects.equals(row.getFingerprint(), digest(row.getManifestJson())),
                "原开工核验结果或消耗模式原文摘要不可核验");
        try {
            var json = JSONUtil.parseObj(row.getManifestJson());
            require("MATERIAL_START_V1".equals(json.get("version"))
                            && String.valueOf(arrange.getId()).equals(json.get("arrangeId"))
                            && row.getMode().equals(json.get("mode")) && row.getResult().equals(json.get("result")),
                    "原开工核验合同、派工或策略与消耗模式原文不一致");
            Object mode = json.get("consumptionMode");
            require("FEED".equals(mode) || "BACKFLUSH".equals(mode), "原开工核验缺少明确消耗模式，不能补造历史");
            return Mode.valueOf((String) mode);
        } catch (CheckedException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CheckedException("原开工消耗模式原文不可解析，不能读取今日全局配置替代");
        }
    }

    public enum Mode {
        FEED,
        BACKFLUSH
    }
}
