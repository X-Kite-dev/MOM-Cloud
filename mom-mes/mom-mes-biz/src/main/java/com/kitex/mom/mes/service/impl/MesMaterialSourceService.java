package com.kitex.mom.mes.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.mes.dto.MesMaterialSourceDTO.Query;
import com.kitex.mom.mes.dto.MesMaterialSourceDTO.Result;
import com.kitex.mom.mes.dto.MesWarehouseLiveStockInoutDTO;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.enums.WarehouseInBizTypeEnum;
import com.kitex.mom.mes.mapper.MesMaterialSourceMapper;
import com.kitex.mom.mes.serialization.MesNativeQuantitySerializer;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.service.MesWarehouseLiveStockService;
import com.kitex.mom.mes.util.MesFrozenExecutionGraph;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 复用当前库存资格和原投料出生证据；下钻本身不占用或扣减物料。
 */
@Service
@RequiredArgsConstructor
public class MesMaterialSourceService {
    private static final ObjectMapper JSON = jsonMapper();
    private final MesActualFactoryAccessService access;
    private final MesMaterialSourceMapper mapper;
    private final MesWarehouseLiveStockService stocks;
    private final MesWarehouseFeedBirthService births;

    private static ObjectMapper jsonMapper() {
        var module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        module.addSerializer(BigDecimal.class, new MesNativeQuantitySerializer());
        return new ObjectMapper().findAndRegisterModules().registerModule(module).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private static Map<String, Object> values(Object source) {
        return JSON.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static Long id(String value) {
        require(value != null && value.matches("[1-9][0-9]{0,18}"), "须提供精确正整数来源ID");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new CheckedException("来源ID超出有效范围");
        }
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new CheckedException(message);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result inspect(Query query) {
        String factory = access.require("read");
        Long tenant = TenantContextHolder.getTenantId();
        require(tenant != null && tenant > 0 && query != null, "来源下钻缺少实际租户或查询");
        Long arrangeId = id(query.getArrangeId());
        Long sourceId = id(query.getSourceId());
        require(Set.of("FEED", "STOCK").contains(Objects.toString(query.getSourceKind(), "")), "来源类型只能是投料或当前库存");
        int current = Objects.requireNonNullElse(query.getLineCurrent(), 1);
        int size = Objects.requireNonNullElse(query.getLineSize(), 20);
        require(current > 0 && current <= 100000 && size > 0 && size <= 100, "来源明细页码或每页数量无效");
        var arrange = mapper.arrange(tenant, factory, arrangeId);
        require(arrange != null && Objects.equals(arrange.getId(), arrangeId)
                && Objects.equals(arrange.getTenantId(), tenant) && Objects.equals(arrange.getFactoryCode(), factory), "本组织未找到原派工");
        var tasks = mapper.tasks(tenant, factory, arrange.getTaskCode());
        var processes = mapper.processes(tenant, factory, arrange.getProduceTaskCode(), arrange.getTaskCode());
        require(tasks.size() == 1 && processes.size() == 1, "原工单/工序未唯一，不能猜来源归属");
        var task = tasks.get(0);
        var process = processes.get(0);
        MesFrozenExecutionGraph.assertArrangement(process, arrange);
        require(Objects.equals(task.getProcessRouteSnapshot(), process.getProcessRouteSnapshot()), "原工单与工序工艺快照不一致");
        var result = new Result().setTenantId(tenant.toString()).setFactoryCode(factory).setObservedAt(LocalDateTime.now())
                .setArrangeId(arrangeId.toString()).setArrangeCode(arrange.getCode()).setTaskCode(task.getCode())
                .setProduceTaskCode(process.getCode()).setSourceKind(query.getSourceKind()).setSourceId(sourceId.toString())
                .setDataGaps(new ArrayList<>());
        if ("FEED".equals(query.getSourceKind())) {
            feed(query, result, task, arrange, tenant, factory, sourceId, current, size);
        } else {
            stock(query, result, task, arrange, tenant, factory, sourceId);
        }
        return result;
    }

    private void feed(Query query, Result result, MesTaskEntity task, MesTaskArrangeEntity arrange,
                      Long tenant, String factory, Long sourceId, int current, int size) {
        var feed = mapper.feed(tenant, factory, sourceId, task.getCode(), arrange.getCode(), arrange.getProduceTaskCode());
        require(feed != null && Objects.equals(feed.getId(), sourceId) && Objects.equals(feed.getTenantId(), tenant)
                && Objects.equals(feed.getFactoryCode(), factory), "投料来源不属于当前原工单、工序及派工");
        String requirement = text(feed.getRequirementMatCode()) ? feed.getRequirementMatCode() : feed.getMatCode();
        require(!text(query.getRequirementMaterialCode()) || Objects.equals(query.getRequirementMaterialCode(), requirement), "投料不属于所选需求物料");
        result.setRequirementMaterialCode(requirement).setSource(values(feed));
        var evidence = births.evidence(sourceId);
        require(evidence != null, "原投料出生服务未返回证据");
        var lineage = new LinkedHashMap<String, Object>();
        lineage.put("status", evidence.status());
        lineage.put("current", current);
        lineage.put("size", size);
        lineage.put("total", evidence.sources().size());
        if ("HISTORICAL_UNPROVEN".equals(evidence.status())) {
            require(evidence.birth() == null && evidence.sources().isEmpty(), "历史未证明来源不能携带另一份出生凭证");
            lineage.put("birth", null);
            lineage.put("sources", List.of());
            result.getDataGaps().add("历史投料没有首次领出原证据；未按当前同批库存猜来源");
        } else {
            var birth = evidence.birth();
            require("RECORDED".equals(evidence.status()) && birth != null
                    && Objects.equals(birth.getTenantId(), tenant) && Objects.equals(birth.getFactoryCode(), factory)
                    && Objects.equals(birth.getFeedId(), sourceId) && Objects.equals(birth.getTaskId(), task.getId())
                    && Objects.equals(birth.getArrangeId(), arrange.getId())
                    && Objects.equals(birth.getSourceCount(), evidence.sources().size()), "投料出生原证据不属于本次精确来源");
            require(evidence.sources().stream().allMatch(row -> Objects.equals(row.getTenantId(), tenant)
                    && Objects.equals(row.getFactoryCode(), factory) && Objects.equals(row.getBirthId(), birth.getId())), "出生逐源证据跨原组织或主账");
            var head = values(birth);
            // 完整原文已由出生服务逐项核验；分页响应不重复携带整份5000源总正文。
            head.remove("evidenceSnapshot");
            head.remove("feedSnapshot");
            head.remove("bomSnapshot");
            head.remove("arrangeSnapshot");
            lineage.put("birth", head);
            long from = (long) (current - 1) * size;
            lineage.put("sources", evidence.sources().stream().skip(from).limit(size).map(MesMaterialSourceService::values).toList());
        }
        result.setLineage(lineage);
        if (feed.getUsedNum() == null) {
            result.getDataGaps().add("原投料已用量未知，未显示为零或推算净耗");
        }
        result.getDataGaps().add("投料是领出来源；实际生产耗用请查看对应GOOD、NG或组合实耗原账");
    }

    private void stock(Query query, Result result, MesTaskEntity task, MesTaskArrangeEntity arrange,
                       Long tenant, String factory, Long sourceId) {
        require(text(query.getRequirementMaterialCode()) && query.getRequirementMaterialCode().length() <= 200,
                "库存下钻须明确原需求物料，不能按共享替代料猜需求");
        var stock = mapper.stock(tenant, factory, sourceId);
        require(stock != null && Objects.equals(stock.getId(), sourceId) && Objects.equals(stock.getTenantId(), tenant)
                && Objects.equals(stock.getFactoryCode(), factory), "当前库存来源不存在或已经耗尽归档，请重新核对齐套");
        var route = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(), arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
        require(route.getMdmProcessProcedureMatList() != null && route.getMdmProcessProcedureMatList().size() <= 100, "原用料清单不明确或范围过大");
        var requirements = route.getMdmProcessProcedureMatList().stream().filter(Objects::nonNull)
                .filter(row -> Objects.equals(row.getMaterialCode(), query.getRequirementMaterialCode())).toList();
        require(requirements.size() == 1, "所选需求不在原工序唯一冻结用料清单中");
        var candidates = MesMaterialSubstituteResolver.resolvePolicy(task.getBomSnapshot(), query.getRequirementMaterialCode())
                .candidates().stream().filter(candidate -> Objects.equals(candidate.actualMatCode(), stock.getMatCode())).toList();
        require(candidates.size() == 1, "库存不是原需求的主料或允许替代料");
        var candidate = candidates.get(0);
        var request = new MesWarehouseLiveStockInoutDTO();
        request.setBizType(WarehouseInBizTypeEnum.BACK_FLUSH_OUT.getCode());
        request.setBillNo(arrange.getCode());
        request.setBillId(arrange.getId());
        request.setProduceTaskCode(arrange.getProduceTaskCode());
        request.setAllowUnassignedProduceTask(true);
        request.setMatDetailType("RAW");
        request.setMatCode(stock.getMatCode());
        request.setRequirementMatCode(query.getRequirementMaterialCode());
        request.setSubstituteRatio(candidate.ratio());
        request.setSubstituteFlag(candidate.substitute());
        request.setMatBatch(stock.getMatBatch());
        request.setWarehouseCode(stock.getWarehouseCode());
        request.setSiteCode(stock.getSiteCode());
        request.setFactoryCode(factory);
        request.setNum(BigDecimal.ONE);
        var eligible = stocks.previewOutCandidates(request);
        require(eligible != null && eligible.stream().anyMatch(row -> Objects.equals(row.getId(), sourceId)
                        && Objects.equals(row.getTenantId(), tenant) && Objects.equals(row.getFactoryCode(), factory)),
                "该库存已不满足本派工需求、预留归属、质量或有效期资格，请重新核对齐套");
        result.setRequirementMaterialCode(query.getRequirementMaterialCode()).setSource(values(stock));
        var lineage = new LinkedHashMap<String, Object>();
        lineage.put("status", "CURRENT_CANDIDATE");
        lineage.put("reservationNo", stock.getReservationNo());
        lineage.put("requirementMaterialCode", query.getRequirementMaterialCode());
        lineage.put("substituteRatio", candidate.ratio().toPlainString());
        result.setLineage(lineage);
        result.getDataGaps().add("本次为当前合格库存观察，不代表已经预留、绑定或扣料；后续实际命令仍须重新核验");
    }
}
