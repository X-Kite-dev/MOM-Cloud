package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.admin.api.feign.RemoteParamService;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesWarehouseFeedCommandDTO;
import com.kitex.mom.mes.dto.MesWarehouseFeedPreparationDTO;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.mapper.MesTaskArrangeMapper;
import com.kitex.mom.mes.mapper.MesTaskMapper;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 非持久化投料观察读取器，避免把实际接收工位/单位加到旧终端VO的计划字段上。
 * 不创建预留、不锁库存、不补历史谱系；真实执行沿原FEED事务重检并冻结实际扣减全集。
 */
@Component
@RequiredArgsConstructor
public class MesWarehouseFeedPreparationReader {
    private final MesTaskArrangeMapper arrangements;
    private final MesTaskMapper tasks;
    private final MesWarehouseLiveStockService stocks;
    private final RemoteMaterialService materials;
    private final RemoteParamService parameters;
    private final MesPhysicalTransformCodec codec;
    private final MesTerminalExecutionGuard terminals;
    private final MesReworkMaterialPlanService reworkPlans;

    /** 在命令服务已核身份/261部署且新建只读一致事务内读取；跨服务计量并非分布式原子快照。 */
    public MesWarehouseFeedPreparationDTO.Observation read(MesWarehouseFeedPreparationDTO.Query query,
                                                           MesWarehouseFeedCommandDTO.Context context) {
        require(query != null, "须明确投料准备条件");
        codec.code(query.getArrangeCode(), "实际派工", 100);
        codec.code(query.getMaterialCode(), "投料物料", 100);
        optional(query.getRequirementMaterialCode(), "BOM需求", 100);
        optional(query.getBatchNo(), "来源批次", 100);
        optional(query.getBarcode(), "来源条码", 200);
        optional(query.getSourceWarehouseCode(), "来源仓库", 100);
        optional(query.getSourceSiteCode(), "来源库位", 100);
        optional(query.getSupplierCode(), "来源供应商", 100);
        optional(query.getReservationNo(), "来源预留号", 100);
        var arranges = arrangements.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, context.factoryCode())
                .eq(MesTaskArrangeEntity::getCode, query.getArrangeCode()).last("LIMIT 2"));
        require(arranges.size() == 1, "派工未唯一归属当前实际租户工厂");
        var arrange = arranges.get(0);
        var parents = tasks.selectList(new LambdaQueryWrapper<MesTaskEntity>()
                .eq(MesTaskEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskEntity::getFactoryCode, context.factoryCode())
                .eq(MesTaskEntity::getCode, arrange.getTaskCode()).last("LIMIT 2"));
        require(parents.size() == 1, "派工的实际父工单缺失或不唯一");
        var task = parents.get(0);
        require(Set.of("DISPATCH", "PRODUCT").contains(Objects.toString(task.getStatus(), ""))
                && Set.of("UN_RECEIVE", "RECEIVE", "PRODUCT", "PAUSE").contains(Objects.toString(arrange.getStatus(), "")),
                "当前工单或派工状态不允许新增投料");
        terminals.assertUnoccupied(arrange);
        reworkPlans.assertReady(task);
        var parameter = parameters.getByKey("BACK_FLUSH");
        require(parameter != null && parameter.isOk() && "0".equals(parameter.getData()),
                "手工投料配置未明确或已启用倒冲，不能准备重复扣库");
        var response = materials.productionMeasure(query.getMaterialCode());
        var measure = response == null ? null : response.getData();
        require(response != null && response.isOk() && measure != null && Boolean.TRUE.equals(measure.getEnabled())
                && Objects.equals(measure.getTenantId(), context.tenantId())
                && Objects.equals(measure.getFactoryCode(), context.factoryCode())
                && Objects.equals(measure.getMaterialCode(), query.getMaterialCode()), "实际物料计量未启用或组织身份不可核验");
        codec.code(measure.getBasicUnit(), "实际基本单位", 100);
        codec.code(arrange.getProduceTaskCode(), "实际工序任务", 100);
        var bindings = MesMaterialSubstituteResolver.feedBindings(task.getBomSnapshot(), query.getMaterialCode(),
                query.getRequirementMaterialCode()).stream()
                .map(b -> new MesWarehouseFeedPreparationDTO.Binding(b.requirementMatCode(), b.actualMatCode(),
                        codec.amount(b.ratio()), b.substitute(), b.allowMixed(), b.strategy())).toList();
        require(bindings.size() <= 1000, "BOM绑定超过1000项，须明确需求而非截断候选");
        var filter = new MesWarehouseLiveStockInoutDTO();
        filter.setMatCode(query.getMaterialCode());
        filter.setFactoryCode(context.factoryCode());
        filter.setMatBatch(query.getBatchNo());
        filter.setMatBarcode(query.getBarcode());
        filter.setWarehouseCode(query.getSourceWarehouseCode());
        filter.setSiteCode(query.getSourceSiteCode());
        filter.setSupplierCode(query.getSupplierCode());
        filter.setReservationNo(query.getReservationNo());
        var candidates = stocks.previewFeedSources(filter);
        require(candidates.stream().allMatch(s -> Objects.equals(s.getMatUnit(), measure.getBasicUnit())),
                "实际计量和来源库存单位观察发生变化，请重新读取，不进行隐式换算");
        var sources = candidates.stream().map(s -> new MesWarehouseFeedPreparationDTO.Source(s.getId().toString(),
                codec.amount(s.getNum()), s.getMatUnit(), s.getMatBatch(), s.getMatBarcode(), s.getWarehouseCode(),
                s.getSiteCode(), s.getSupplierCode(), s.getReservationNo(), s.getRequirementMatCode(),
                id(s.getMaterialReceiptId()), id(s.getSourceReportId()), s.getProduceTaskCode(),
                s.getInTime() == null ? null : s.getInTime().toString())).toList();
        return new MesWarehouseFeedPreparationDTO.Observation("MES_FEED_PREPARATION_V1", context,
                LocalDateTime.now().toString(), task.getId().toString(), task.getCode(), task.getStatus(),
                arrange.getId().toString(), arrange.getCode(), arrange.getStatus(), arrange.getProduceTaskCode(),
                arrange.getActualWorksiteCode() == null ? "" : arrange.getActualWorksiteCode(),
                measure.getMaterialId(), measure.getMaterialCode(), measure.getBasicUnit(), measure.getUpdatedAt(), bindings, sources);
    }

    /** 空值代表不筛选；非空编码不修剪、不把NULL谱系转换成空文本。 */
    private void optional(String value, String name, int limit) {
        if (value != null && !value.isEmpty()) codec.code(value, name, limit);
    }

    private String id(Long value) {
        return value == null ? null : value.toString();
    }
}
