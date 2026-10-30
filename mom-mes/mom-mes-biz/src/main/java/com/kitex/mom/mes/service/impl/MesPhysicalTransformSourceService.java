package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.dto.MesPhysicalTransformDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskReportEntity;
import com.kitex.mom.mes.entity.MesWarehouseLiveStockEntity;
import com.kitex.mom.mes.entity.MesWipTransferEntity;
import com.kitex.mom.mes.enums.MesProduceTaskStatusEnum;
import com.kitex.mom.mes.enums.ReportModeEnum;
import com.kitex.mom.mes.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 实物转换的同范围完整来源读取和数学预检，全部事务只读。
 * 本组件不持久化业务实体，不是IService CRUD服务；永久转换执行服务在后续独立建设。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalTransformSourceService {

    private final MesActualFactoryAccessService access;
    private final MesWarehouseLiveStockMapper stocks;
    private final MesTaskReportMapper reports;
    private final MesTaskArrangeMapper arranges;
    private final MesWipTransferMapper transfers;
    private final MesTerminalExecutionCommandMapper terminalCommands;
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformPlanner planner;

    /**
     * 一致快照观察完整精确来源；同码行超界拒绝，不用LIMIT悄悄遗漏对象余额。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesPhysicalTransformDTO.SourceView sources(List<String> stockIds) {
        String factory = access.require("read");
        require(stockIds != null && !stockIds.isEmpty() && stockIds.size() <= MesPhysicalTransformCodec.MAX_SOURCES,
                "须提供1至50个精确来源库存ID");
        List<Long> ids = stockIds.stream().map(codec::id).sorted().toList();
        require(new HashSet<>(ids).size() == ids.size(), "来源库存ID不能重复");
        var rows = stocks.selectList(stockScope(factory).in(MesWarehouseLiveStockEntity::getId, ids)
                .orderByAsc(MesWarehouseLiveStockEntity::getId));
        require(rows.size() == ids.size(), "精确来源缺失、已删除或不在实际组织；禁止猜选同批库存");
        List<MesPhysicalTransformDTO.SourceFact> facts = new ArrayList<>();
        for (var row : rows) facts.add(source(row, factory, Set.copyOf(ids)));
        var result = new MesPhysicalTransformDTO.SourceView();
        result.setVersion("MES_PHYSICAL_SOURCE_VIEW_V1");
        result.setTenantId(TenantContextHolder.getTenantId().toString());
        result.setFactoryCode(factory);
        result.setActorUserId(SecurityUtils.getUser().getId().toString());
        result.setActorCode(SecurityUtils.getUser().getUsername());
        result.setSources(facts);
        return result;
    }

    /**
     * 预检不会保存原号/库存，不以客户端政策草案开放正式转换。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MesPhysicalTransformDTO.Preview preview(String rawJson) {
        var plan = codec.decode(rawJson);
        var sourceView = sources(plan.getInputs().stream().map(MesPhysicalTransformDTO.Input::getStockId).toList());
        codec.id(plan.getExpectedTenantId());
        codec.id(plan.getExpectedActorUserId());
        codec.code(plan.getExpectedFactoryCode(), "期望工厂", 100);
        require(sourceView.getTenantId().equals(plan.getExpectedTenantId())
                        && sourceView.getFactoryCode().equals(plan.getExpectedFactoryCode())
                        && sourceView.getActorUserId().equals(plan.getExpectedActorUserId()),
                "观察后的实名或组织已改变，不能用新请求头套用原转换来源");
        var policy = codec.policy(plan.getPolicyDraft());
        var calculation = planner.calculate(plan, policy, sourceView.getSources());
        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("sourceView", sourceView);
        evidence.put("plan", plan);
        evidence.put("policy", policy);
        evidence.put("calculation", calculation);
        var result = new MesPhysicalTransformDTO.Preview();
        result.setVersion(MesPhysicalTransformCodec.PREVIEW_VERSION);
        result.setSourceView(sourceView);
        result.setCalculation(calculation);
        result.setPreviewSnapshot(codec.snapshot(evidence));
        result.setFingerprint(codec.fingerprint(MesPhysicalTransformCodec.PREVIEW_VERSION, result.getPreviewSnapshot()));
        result.setExecutionAvailable(Boolean.FALSE);
        result.setMessage("仅完整来源观察与精确数学预检；未登记转换、生成实物或写库存。真实冻结工艺、位置余额、身份冲突及下游谱系尚须执行事务独立验证，预检SHA不是生产许可。");
        return result;
    }

    private MesPhysicalTransformDTO.SourceFact source(MesWarehouseLiveStockEntity stock, String factory, Set<Long> selected) {
        List<String> blocked = new ArrayList<>();
        var report = stock.getSourceReportId() == null ? null : reports.selectOne(new LambdaQueryWrapper<MesTaskReportEntity>()
                .eq(MesTaskReportEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskReportEntity::getFactoryCode, factory).eq(MesTaskReportEntity::getId, stock.getSourceReportId()));
        List<MesTaskArrangeEntity> matches = report == null || blank(report.getArrangeCode()) ? List.of()
                : arranges.selectList(new LambdaQueryWrapper<MesTaskArrangeEntity>()
                .eq(MesTaskArrangeEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesTaskArrangeEntity::getFactoryCode, factory)
                .eq(MesTaskArrangeEntity::getCode, report.getArrangeCode()).last("LIMIT 2"));
        var arrange = matches.size() == 1 ? matches.get(0) : null;
        check(blocked, "WIP".equals(stock.getMatDetailType()), "来源不是在制实物，成品/原料须走各自业务");
        check(blocked, stock.getNum() != null && stock.getNum().signum() > 0, "实际来源量为空、零或负数");
        check(blocked, "0".equals(stock.getStatus()), "来源库存已锁定或可用状态未确认");
        check(blocked, stock.getQualityCaseId() == null, "来源存在独立实物质量案件");
        check(blocked, Set.of("RELEASED", "NOT_REQUIRED").contains(Objects.toString(stock.getInspectionStatus(), "")), "来源质量未放行或历史状态未明确");
        check(blocked, blank(stock.getJudgeStatus()), "来源在退料/审批处置中");
        check(blocked, stock.getMatValidEndDate() == null || !stock.getMatValidEndDate().isBefore(LocalDate.now()), "来源已过有效期");
        check(blocked, !blank(stock.getMatCode()) && !blank(stock.getMatUnit()), "来源物料或单位未明确");
        check(blocked, report != null && "EFFECTIVE".equals(report.getStatus()), "原报工缺失、已冲销或历史有效性未确认");
        check(blocked, arrange != null, "原派工不存在或同组织编码不唯一");
        ReportModeEnum mode = null;
        List<MesWarehouseLiveStockEntity> originStocks = List.of();
        if (report != null) {
            check(blocked, !blank(report.getTaskCode()) && !blank(report.getProduceTaskCode()), "原报工工单或工序任务未明确");
            BigDecimal effective = report.getEffectiveReportNum() == null ? report.getReportNum() : report.getEffectiveReportNum();
            check(blocked, effective != null && effective.signum() > 0 && stock.getNum() != null
                    && stock.getNum().compareTo(effective) <= 0, "原报工有效数量未明确或来源量超过原有效产量");
            originStocks = stocks.selectList(stockScope(factory).eq(MesWarehouseLiveStockEntity::getSourceReportId, report.getId())
                    .eq(MesWarehouseLiveStockEntity::getMatCode, report.getMatCode())
                    .gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO)
                    .orderByAsc(MesWarehouseLiveStockEntity::getId).last("LIMIT 2001"));
            require(originStocks.size() <= 2000, "原报工正量来源超过2000行，拒绝截断，请核对分配谱系");
            BigDecimal liveOriginQuantity = originStocks.stream().map(MesWarehouseLiveStockEntity::getNum)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            check(blocked, effective != null && liveOriginQuantity.compareTo(effective) <= 0,
                    "同原报工全部正量库存超过有效产量，不能合并重复产出");
            check(blocked, originStocks.stream().allMatch(origin -> Objects.equals(origin.getMatUnit(), report.getReportUnit())),
                    "同原报工来源存在不同单位，尚无真实转换分配依据");
            check(blocked, Objects.equals(stock.getMatCode(), report.getMatCode())
                    && Objects.equals(stock.getBillNo(), report.getArrangeCode())
                    && Objects.equals(stock.getProduceTaskCode(), report.getProduceTaskCode()), "库存物料/派工/工序与原报工归属不一致");
            check(blocked, !blank(report.getReportUnit()) && Objects.equals(stock.getMatUnit(), report.getReportUnit()), "原报工单位未冻结或与库存单位不一致");
            try {
                mode = ReportModeEnum.fromCode(report.getReportMode());
            } catch (RuntimeException exception) {
                blocked.add("原报工对象模式未冻结，不能用当前模板补造历史");
            }
            if (mode != null && mode.isIdentifierRequired())
                check(blocked, !blank(report.getTransferCode()) && !blank(stock.getMatBarcode())
                                && Objects.equals(stock.getMatBarcode(), report.getTransferCode()),
                        "库存实物标识为空或与原报工规范标识不一致，不能借用另一实物来源");
        }
        boolean mobileOccupied = false;
        if (arrange != null) {
            // 正常完工不抹掉既有实物；待开工、关闭及未知派工不能证明有效生产来源。
            check(blocked, Set.of(MesProduceTaskStatusEnum.PRODUCT.name(), MesProduceTaskStatusEnum.PAUSE.name(),
                            MesProduceTaskStatusEnum.FINISH.name()).contains(Objects.toString(arrange.getStatus(), "")),
                    "原派工未实际开工、已关闭或状态未确认");
            check(blocked, report != null && Objects.equals(arrange.getTaskCode(), report.getTaskCode())
                    && Objects.equals(arrange.getProduceTaskCode(), stock.getProduceTaskCode())
                    && Objects.equals(arrange.getMatCode(), stock.getMatCode()), "真实派工与原库存/报工工单来源不一致");
            mobileOccupied = !terminalCommands.pending(TenantContextHolder.getTenantId(), factory, arrange.getId()).isEmpty();
            check(blocked, !mobileOccupied, "原派工存在移动执行未决原号，仅查证，不按当前状态猜结果");
        }
        List<MesWarehouseLiveStockEntity> peers = List.of(stock);
        List<MesWipTransferEntity> transit = List.of();
        if (mode != null && mode.isIdentifierRequired() && !blank(stock.getMatBarcode())) {
            peers = stocks.selectList(stockScope(factory).eq(MesWarehouseLiveStockEntity::getMatBarcode, stock.getMatBarcode())
                    .gt(MesWarehouseLiveStockEntity::getNum, BigDecimal.ZERO)
                    .orderByAsc(MesWarehouseLiveStockEntity::getId).last("LIMIT 2001"));
            require(peers.size() <= 2000, "同实物正量来源超过2000行，拒绝截断，请核查标识域");
            check(blocked, peers.stream().allMatch(peer -> selected.contains(peer.getId())), "同一实物的正量库存全集未选齐，不能只挑一行当完整母件");
            check(blocked, peers.stream().allMatch(peer -> Objects.equals(peer.getProduceTaskCode(), stock.getProduceTaskCode())
                            && Objects.equals(peer.getMatCode(), stock.getMatCode()) && "WIP".equals(peer.getMatDetailType())),
                    "同标识跨多个工序/物料/库存域，当前实物归属未治理，不能猜最新行");
            if (mode == ReportModeEnum.ONE_CODE)
                check(blocked, peers.size() == 1 && stock.getNum() != null && stock.getNum().compareTo(BigDecimal.ONE) == 0,
                        "单件SN存在多个正量来源或数量不为1");
            transit = transfers.selectList(new LambdaQueryWrapper<MesWipTransferEntity>()
                    .eq(MesWipTransferEntity::getTenantId, TenantContextHolder.getTenantId())
                    .eq(MesWipTransferEntity::getFactoryCode, factory)
                    .eq(MesWipTransferEntity::getMatBarcode, stock.getMatBarcode())
                    .ne(MesWipTransferEntity::getStatus, "CLOSED").last("LIMIT 2001"));
            require(transit.size() <= 2000, "同实物在途来源超过2000行，拒绝截断");
            check(blocked, transit.isEmpty(), "同一实物存在未结清在途/退回/隔离，不能转换其标识");
        }
        Map<String, Object> evidence = new TreeMap<>();
        evidence.put("stock", codec.facts(stock));
        evidence.put("report", codec.facts(report));
        evidence.put("arrange", codec.facts(arrange));
        evidence.put("peerStocks", peers.stream().map(codec::facts).toList());
        evidence.put("originStocks", originStocks.stream().map(codec::facts).toList());
        evidence.put("transit", transit.stream().map(codec::facts).toList());
        evidence.put("mobileOccupied", Boolean.toString(mobileOccupied));
        var result = new MesPhysicalTransformDTO.SourceFact();
        result.setStockId(stock.getId().toString());
        result.setSourceReportId(stock.getSourceReportId() == null ? null : stock.getSourceReportId().toString());
        result.setTaskCode(report == null ? null : report.getTaskCode());
        result.setIdentifier(stock.getMatBarcode());
        result.setMaterialCode(stock.getMatCode());
        result.setUnit(stock.getMatUnit());
        result.setQuantity(stock.getNum() == null ? null : stock.getNum().toPlainString());
        result.setReportMode(report == null ? null : report.getReportMode());
        result.setPeerStockIds(peers.stream().map(peer -> peer.getId().toString()).toList());
        result.setFactSnapshot(codec.snapshot(evidence));
        result.setFingerprint(codec.fingerprint(MesPhysicalTransformCodec.SOURCE_VERSION, result.getFactSnapshot()));
        result.setSourceChecksPassed(blocked.isEmpty());
        result.setBlockingReasons(List.copyOf(blocked));
        return result;
    }

    private LambdaQueryWrapper<MesWarehouseLiveStockEntity> stockScope(String factory) {
        return new LambdaQueryWrapper<MesWarehouseLiveStockEntity>()
                .eq(MesWarehouseLiveStockEntity::getTenantId, TenantContextHolder.getTenantId())
                .eq(MesWarehouseLiveStockEntity::getFactoryCode, factory);
    }

    private void check(List<String> messages, boolean condition, String message) {
        if (!condition) messages.add(message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
