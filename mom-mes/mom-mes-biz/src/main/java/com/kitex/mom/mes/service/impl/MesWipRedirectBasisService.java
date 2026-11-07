package com.kitex.mom.mes.service.impl;

import static com.kitex.mom.mes.service.impl.MesReceivingTargetRules.*;
import static com.kitex.mom.mes.service.impl.MesWipQuantityRules.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.entity.*;
import com.kitex.mom.mes.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/** 改向专用的持久策略和真实报工单位依据；不为旧配送创建补造或迁移策略。 */
@Service
@RequiredArgsConstructor
public class MesWipRedirectBasisService {
    private final MesTaskArrangeMapper arranges;
    private final MesWipRedirectBasisMapper reports;
    private final RemoteMaterialService materials;

    public record Policy(boolean overReportAllowed, BigDecimal overReportRate) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Policy verify(MesWipTransferEntity t, String arrangeId) {
        var a =
                arranges.selectOne(
                        new LambdaQueryWrapper<MesTaskArrangeEntity>()
                                .eq(MesTaskArrangeEntity::getTenantId, t.getTenantId())
                                .eq(MesTaskArrangeEntity::getFactoryCode, t.getFactoryCode())
                                .eq(MesTaskArrangeEntity::getDelFlag, "0")
                                .eq(MesTaskArrangeEntity::getId, id(arrangeId)));
        need(
                a != null
                        && Objects.equals(a.getId(), id(arrangeId))
                        && Objects.equals(a.getTenantId(), t.getTenantId())
                        && Objects.equals(a.getFactoryCode(), t.getFactoryCode())
                        && "0".equals(a.getDelFlag()),
                "目标派工不在原单实际范围");
        var policy = policy(a);
        var source = reports.source(t.getTenantId(), t.getFactoryCode(), t.getSourceReportId());
        need(
                source != null
                        && Objects.equals(source.getId(), t.getSourceReportId())
                        && Objects.equals(source.getTenantId(), t.getTenantId())
                        && Objects.equals(source.getFactoryCode(), t.getFactoryCode())
                        && "0".equals(source.getDelFlag())
                        && "EFFECTIVE".equals(source.getStatus())
                        && Objects.equals(source.getMatCode(), t.getMatCode())
                        && Objects.equals(source.getArrangeCode(), t.getArrangeCode())
                        && Objects.equals(source.getProduceTaskCode(), t.getProduceTaskCode())
                        && Objects.equals(source.getTaskCode(), a.getTaskCode())
                        && code(source.getReportUnit())
                        && source.getReportUnit().equals(t.getMatUnit()),
                "源报工与在途实物单位或业务身份不一致");
        var response = materials.productionMeasure(t.getMatCode());
        var material = response == null ? null : response.getData();
        need(
                response != null
                        && response.isOk()
                        && material != null
                        && Boolean.TRUE.equals(material.getEnabled())
                        && Objects.equals(material.getTenantId(), t.getTenantId().toString())
                        && Objects.equals(material.getFactoryCode(), t.getFactoryCode())
                        && Objects.equals(material.getMaterialCode(), t.getMatCode())
                        && code(material.getBasicUnit())
                        && material.getBasicUnit().equals(t.getMatUnit()),
                "目标当前报工单位与源报工/转运不一致，不能直接比较数量");
        id(material.getMaterialId());
        var groups = reports.targetUnits(t.getTenantId(), t.getFactoryCode(), a.getCode());
        need(groups != null && groups.size() <= 1, "目标已有报工存在混合身份或单位");
        BigDecimal completed = BigDecimal.ZERO;
        for (var g : groups) {
            need(
                    g != null
                            && Objects.equals(g.taskCode(), a.getTaskCode())
                            && Objects.equals(g.operationCode(), a.getProduceTaskCode())
                            && Objects.equals(g.matCode(), a.getMatCode())
                            && Objects.equals(g.unit(), t.getMatUnit())
                            && g.rowCount() > 0
                            && g.rowCount() == g.quantityCount(),
                    "目标已有报工单位、身份或数量原件不完整");
            completed = completed.add(number(g.quantity(), true));
        }
        need(same(completed, a.getCompleteNum()), "目标累计已报数量缺少同单位有效报工依据");
        return policy;
    }

    private static Policy policy(MesTaskArrangeEntity a) {
        try {
            String raw = a.getExecutionPolicySnapshot();
            need(
                    raw != null && !raw.isBlank() && raw.length() <= 16 * 1024 * 1024,
                    "目标派工未持久保存原执行策略");
            var mapper =
                    new ObjectMapper()
                            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var p = mapper.readTree(raw);
            need(
                    p.isObject()
                            && p.path("arrangeCode").isTextual()
                            && a.getCode().equals(p.get("arrangeCode").textValue())
                            && p.path("overReportAllowed").isBoolean(),
                    "原执行策略不属于该派工或超报开关缺失");
            var rate = p.get("overReportRate");
            need(rate != null && (rate.isNumber() || rate.isTextual()), "原执行策略超报比例缺失");
            BigDecimal n =
                    rate.isNumber()
                            ? number(rate.decimalValue(), true)
                            : number(rate.textValue(), true);
            boolean allowed = p.get("overReportAllowed").booleanValue();
            need(
                    n.compareTo(BigDecimal.ONE) <= 0 && (allowed || n.signum() == 0),
                    "原执行策略超报开关与比例矛盾");
            if (p.hasNonNull("produceTaskCode"))
                need(
                        p.get("produceTaskCode").isTextual()
                                && Objects.equals(
                                        p.get("produceTaskCode").textValue(),
                                        a.getProduceTaskCode()),
                        "原执行策略工序任务不一致");
            if (p.hasNonNull("sourceRouteNodeCode"))
                need(
                        p.get("sourceRouteNodeCode").isTextual()
                                && Objects.equals(
                                        p.get("sourceRouteNodeCode").textValue(),
                                        a.getSourceRouteNodeCode()),
                        "原执行策略稳定节点不一致");
            return new Policy(allowed, n);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("目标原执行策略损坏", e);
        }
    }
}
