package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.entity.MesStartMaterialBindingEntity;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.mapper.MesStartMaterialBindingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 耗料和复工共用的不可变来源池读取；无生命周期/模式服务依赖。
 */
@Component
@RequiredArgsConstructor
public class MesStartMaterialBindingReader {
    private final MesStartMaterialBindingMapper mapper;
    private final MesStartMaterialBindingCodec codec;
    private final MesActualFactoryAccessService access;
    private final MesMaterialBindingPoolReader pools;

    public void constrain(MesTaskArrangeEntity arrange, LambdaQueryWrapper<MesWarehouseFeedEntity> query) {
        var ids = sourceIds(arrange);
        if (ids != null) {
            query.in(MesWarehouseFeedEntity::getId, ids);
        }
    }

    public Set<Long> sourceIds(MesTaskArrangeEntity arrange) {
        var row = binding(arrange);
        if (row == null) {
            return null;
        }
        var pool = pools.pool(row, arrange);
        return Collections.unmodifiableSet(new LinkedHashSet<>(pool.feedIds().stream().map(Long::valueOf).toList()));
    }

    public void assertNgAccounting(MesTaskArrangeEntity arrange, String mode) {
        require(sourceIds(arrange) == null || "FEED".equals(mode), "本派工已绑定生产批次投料来源，不良新执行必须使用FEED实际耗料");
    }

    public MesStartMaterialBindingEntity binding(MesTaskArrangeEntity arrange) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(arrange != null && arrange.getId() != null && Objects.equals(tenant, arrange.getTenantId())
                && Objects.equals(factory, arrange.getFactoryCode()), "来源池读取须属于当前实际租户工厂派工");
        require(Boolean.TRUE.equals(mapper.ready()), "开工绑定目录保护未就绪，不能推断为未绑定");
        var rows = mapper.byArrange(tenant, factory, arrange.getId());
        require(rows != null && rows.size() <= 1, "派工开工来源绑定不唯一或无法读取");
        if (rows.isEmpty()) {
            return null;
        }
        var row = rows.get(0);
        verified(row);
        require(Objects.equals(row.getTenantId(), tenant) && Objects.equals(row.getFactoryCode(), factory)
                && Objects.equals(row.getArrangeId(), arrange.getId()) && Objects.equals(row.getArrangeCode(), arrange.getCode())
                && Objects.equals(row.getTaskCode(), arrange.getTaskCode()) && Objects.equals(row.getProduceTaskCode(), arrange.getProduceTaskCode())
                && Objects.equals(row.getRouteNodeCode(), arrange.getSourceRouteNodeCode())
                && Objects.equals(row.getProductBatchNo(), arrange.getBatchNo()), "原派工身份或生产批次与不可变绑定不一致");
        return row;
    }

    public Map<String, Object> verified(MesStartMaterialBindingEntity row) {
        require(row != null && row.getId() != null && row.getId() > 0 && row.getTenantId() != null
                && row.getOperatorId() != null && row.getStartedAt() != null && row.getMaterialGateRecordId() != null
                && "0".equals(row.getDelFlag()), "开工来源绑定原账缺失或损坏");
        var document = codec.document(row.getBindingJson(), row.getBindingFingerprint());
        var command = codec.command((String) document.get("commandJson"));
        require(Objects.equals(codec.requestHash(command), row.getRequestHash())
                && Objects.equals(command.getRequestId(), row.getRequestId())
                && Objects.equals(command.getArrangeId(), row.getArrangeId().toString())
                && Objects.equals(command.getExpectedTenantId(), row.getTenantId().toString())
                && Objects.equals(command.getExpectedFactoryCode(), row.getFactoryCode())
                && Objects.equals(command.getExpectedActorId(), row.getOperatorId().toString())
                && Objects.equals(command.getExpectedPreviewFingerprint(), row.getPreviewFingerprint()), "开工绑定原命令与永久身份或摘要不同");
        var physical = new MesPhysicalTransformCodec();
        require(Objects.equals(physical.snapshot(command), physical.snapshot(document.get("command"))), "绑定原命令文本与对象不同");
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("tenantId", row.getTenantId().toString());
        expected.put("factoryCode", row.getFactoryCode());
        expected.put("actorId", row.getOperatorId().toString());
        expected.put("arrangeId", row.getArrangeId().toString());
        expected.put("arrangeCode", row.getArrangeCode());
        expected.put("taskCode", row.getTaskCode());
        expected.put("produceTaskCode", row.getProduceTaskCode());
        expected.put("routeNodeCode", row.getRouteNodeCode());
        expected.put("productBatchNo", row.getProductBatchNo());
        expected.put("previewFingerprint", row.getPreviewFingerprint());
        expected.put("materialGateRecordId", row.getMaterialGateRecordId().toString());
        expected.put("startedAt", row.getStartedAt().toString());
        require(expected.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(), document.get(e.getKey()))), "开工绑定正文与永久主账归属不同");
        Object selected = document.get("selectedSources");
        require(selected instanceof List<?> && ((List<?>) selected).size() == command.getFeedIds().size(), "绑定来源全集缺失或超出原命令");
        var ids = new ArrayList<String>();
        for (Object source : (List<?>) selected) {
            require(source instanceof Map<?, ?>, "绑定来源身份损坏");
            var facts = (Map<?, ?>) source;
            String feedId = Objects.toString(facts.get("feedId"), null);
            physical.id(feedId);
            physical.id(Objects.toString(facts.get("birthId"), null));
            codec.hash(Objects.toString(facts.get("birthFingerprint"), null));
            ids.add(feedId);
        }
        require(ids.equals(command.getFeedIds()), "绑定精确来源与原命令全集不一致");
        return document;
    }
}
