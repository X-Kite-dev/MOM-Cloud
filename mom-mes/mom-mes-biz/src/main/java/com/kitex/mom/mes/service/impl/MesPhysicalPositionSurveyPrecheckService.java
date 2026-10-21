package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mdm.support.MdmPhysicalTransformStandardCodec;
import com.kitex.mom.mes.dto.MesPhysicalPositionSurveyDTO;
import com.kitex.mom.mes.dto.MesPhysicalTransformSourceBundleDTO;
import com.kitex.mom.mes.enums.ReportModeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 真实来源锁内的位置采集预检：一个母件对象、全部精确库存及逐位置完整当量。
 * 不调用旧单stock DEPANEL算法，不改旧SOURCE/COMMAND原文字段，不写库存或增加PASS。
 * 本组件只形成完整观察；永久登记及异人确认由独立领域服务继续建设。
 */
@Service
@RequiredArgsConstructor
public class MesPhysicalPositionSurveyPrecheckService {
    private final MesPhysicalPositionSurveyCodec surveyCodec;
    private final MesPhysicalTransformCodec codec;
    private final MesPhysicalTransformSourceFreezeService sources;

    /**
     * 真实FOR UPDATE观察不能标成PG只读事务；没有永久位置/claim/余额变更。
     * 观察结束释放锁，后续登记须服务器重新调用，不能保存客户端预检结果。
     */
    @PreAuthorize("@pms.hasPermission('mes_physicalTransform_operate')")
    @Transactional(rollbackFor = Exception.class)
    public MesPhysicalPositionSurveyDTO.Preview preview(String rawJson) {
        var body = surveyCodec.body(rawJson);
        var bundle = sources.lockAndRead(body.getSources().stream().map(MesPhysicalPositionSurveyDTO.Source::getStockId).toList());
        return validateLocked(rawJson, body, bundle);
    }

    /** 仅消费服务器在本次真实写域/工单/来源锁内读取的包，不开放客户端bundle参数入口。 */
    MesPhysicalPositionSurveyDTO.Preview validateLocked(String rawJson, MesPhysicalPositionSurveyDTO.Body body,
                                                        MesPhysicalTransformSourceBundleDTO bundle) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "位置预检必须沿本次服务器来源锁事务调用");
        require(body.getExpectedTenantId().equals(bundle.getTenantId())
                && body.getExpectedFactoryCode().equals(bundle.getFactoryCode())
                && body.getExpectedActorUserId().equals(bundle.getActorUserId()), "实际位置观察的租户/工厂/本人已改变");
        require(MesPhysicalTransformSourceFreezeService.BUNDLE_VERSION.equals(bundle.getVersion())
                && body.getSources().size() == bundle.getSources().size(), "位置采集服务器来源版本/全集不一致");
        var sourceIds = new HashSet<String>();
        var equivalents = new TreeMap<String, BigDecimal>();
        var limitations = new LinkedHashSet<String>();
        String canonical = null;
        String material = null;
        String configuration = null;
        String standardId = null;
        String standardSha = null;
        String layoutCode = null;
        Set<String> rootReports = new HashSet<>();
        BigDecimal nativeTotal = BigDecimal.ZERO;
        BigDecimal effectiveReportQuantity = null;
        for (var input : body.getSources()) {
            var matching = bundle.getSources().stream().filter(entry -> input.getStockId().equals(entry.getStockId())).toList();
            require(matching.size() == 1, "每个精确来源必须唯一，不能按条码猜库存");
            var entry = matching.get(0);
            require(MesPhysicalTransformSourceFreezeService.SOURCE_VERSION.equals(entry.getSourceVersion())
                    && input.getExpectedFingerprint().equals(entry.getSourceFingerprint()), "原位置来源指纹已改变，请重新观察");
            require(entry.getPolicyConfiguration() != null && !MesPhysicalTransformPolicyValidator.NONE.equals(entry.getPolicyConfiguration())
                    && entry.getStandardId() != null && entry.getStandardFingerprint() != null,
                    "原派工缺真实冻结工程，不能用当前布局给旧NONE补转换依据");
            var policy = codec.policy(entry.getPolicyConfiguration());
            require("DEPANEL".equals(policy.getOperationType()) && "PANEL".equals(policy.getInputObjectType())
                    && "SN".equals(policy.getOutputObjectType()), "位置记录须沿原PANEL至SN完整工程，不借包装/载具造母件");
            var fact = entry.getSource();
            require(fact != null && ReportModeEnum.TRANSFER_CARD == ReportModeEnum.fromCode(fact.getReportMode()),
                    "原母件须有实际流转卡身份，不能从数量批次或包装模式猜母件");
            codec.code(fact.getIdentifier(), "实际规范母件标识", 100);
            codec.id(fact.getSourceReportId());
            require(policy.getInputUnit().equals(fact.getUnit()), "实际母件库存单位与原工程不同");
            var sourceRaw = codec.objectDocument(entry.getSourceSnapshot(), MesPhysicalPositionSurveyCodec.MAX_DOCUMENT);
            var origin = codec.objectDocument(sourceRaw.path("originSnapshot").textValue(), MesPhysicalPositionSurveyCodec.MAX_DOCUMENT);
            require("EFFECTIVE".equals(sourceRaw.path("report").path("status").textValue())
                    && fact.getIdentifier().equals(sourceRaw.path("report").path("transferCode").textValue()),
                    "母件原报工已失效或规范标识不属于实际来源");
            require("WIP".equals(origin.path("stock").path("matDetailType").textValue())
                    && Objects.equals(fact.getMaterialCode(), sourceRaw.path("report").path("matCode").textValue())
                    && Objects.equals(fact.getUnit(), sourceRaw.path("report").path("reportUnit").textValue()),
                    "位置来源不是有原冻结物料/单位的真实在制库存，不能用未知旧单位解释实物");
            BigDecimal nativeQuantity = codec.quantity(fact.getQuantity(), false, "真实母件库存量");
            nativeTotal = nativeTotal.add(nativeQuantity);
            codec.amount(nativeTotal);
            String effective = sourceRaw.path("report").path("effectiveReportNum").textValue();
            if (effective == null) {
                effective = sourceRaw.path("report").path("reportNum").textValue();
            }
            BigDecimal reportQuantity = codec.quantity(effective, false, "原有效报工数量");
            if (effectiveReportQuantity == null) {
                effectiveReportQuantity = reportQuantity;
            } else {
                require(effectiveReportQuantity.compareTo(reportQuantity) == 0, "同母件原有效报工事实不一致");
            }
            var standard = sourceRaw.path("standard");
            require(standard.path("contentSnapshot").isTextual(), "原正式工程完整正文缺失");
            String standardRaw = standard.get("contentSnapshot").textValue();
            require(entry.getStandardFingerprint().equals(MdmPhysicalTransformStandardCodec.fingerprint(
                    MdmPhysicalTransformStandardCodec.VERSION, standardRaw)), "原工程全文SHA不一致");
            String currentLayout = codec.objectDocument(standardRaw, 4 * 1024 * 1024).path("positionLayoutCode").textValue();
            codec.code(currentLayout, "原实际工程布局", 100);
            if (configuration == null) {
                canonical = fact.getIdentifier();
                material = fact.getMaterialCode();
                configuration = entry.getPolicyConfiguration();
                standardId = entry.getStandardId();
                standardSha = entry.getStandardFingerprint();
                layoutCode = currentLayout;
            } else {
                require(Objects.equals(canonical, fact.getIdentifier()) && Objects.equals(material, fact.getMaterialCode())
                        && configuration.equals(entry.getPolicyConfiguration()) && standardId.equals(entry.getStandardId())
                        && standardSha.equals(entry.getStandardFingerprint()) && layoutCode.equals(currentLayout),
                        "一个母件的来源标识/物料/原完整工程不同，不能借同码合并多个对象");
            }
            BigDecimal amount = nativeQuantity.multiply(codec.quantity(policy.getEquivalenceFactor(), false, "原工程当量"));
            codec.amount(amount);
            // 本版沿原来源整数SN边界；不支持将两条各0.5当量的库存重组一个位置。
            // 这是逐库存的独立限制，不是把库存计量当成母件对象数量。
            require(amount.stripTrailingZeros().scale() <= 0, "本版每条精确库存须为整数SN当量，尚不支持跨库存分数重组");
            equivalents.put(input.getStockId(), amount);
            sourceIds.add(input.getStockId());
            rootReports.add(fact.getSourceReportId());
            limitations.addAll(entry.getBlockingReasons());
        }
        // 旧来源合同只证明原报工根；多个根必须另有永久母件产生/全部分配事实，不能挑首根。
        require(rootReports.size() == 1, "多个原报工尚缺实际母件形成/分配依据，不能把同码报告猜成同一个母件");
        require(effectiveReportQuantity != null && nativeTotal.compareTo(effectiveReportQuantity) <= 0,
                "母件全部正量来源超过原有效产量，不能用重复库存凑位置或扩大PASS");
        for (var entry : bundle.getSources()) {
            require(sourceIds.containsAll(entry.getSource().getPeerStockIds()), "母件全部正量库存未选齐，不能只采集合格行遗漏隔离实物");
        }
        var policy = codec.policy(configuration);
        var observedPositions = new HashSet<String>();
        var allocated = new TreeMap<String, BigDecimal>();
        int stocked = 0;
        int unstocked = 0;
        int missing = 0;
        int unknown = 0;
        for (var slot : body.getPositions()) {
            require(policy.getPositionCodes().contains(slot.getPositionCode()) && observedPositions.add(slot.getPositionCode()),
                    "位置不属于原完整布局或重复");
            if ("STOCKED".equals(slot.getPresence())) {
                BigDecimal total = BigDecimal.ZERO;
                for (var edge : slot.getAllocations()) {
                    BigDecimal amount = codec.quantity(edge.getQuantity(), false, "逐位来源当量");
                    total = total.add(amount);
                    allocated.merge(edge.getStockId(), amount, BigDecimal::add);
                    codec.amount(total);
                    codec.amount(allocated.get(edge.getStockId()));
                }
                require(total.compareTo(BigDecimal.ONE) == 0, "每个实际库存化位置必须是一个完整SN当量");
                stocked++;
            } else if ("UNSTOCKED".equals(slot.getPresence())) {
                unstocked++;
            } else if ("MISSING".equals(slot.getPresence())) {
                missing++;
            } else {
                unknown++;
            }
        }
        require(observedPositions.equals(Set.copyOf(policy.getPositionCodes())), "必须明确完整布局每一位置，不按部分页面当完整母件");
        equivalents.forEach((id, amount) -> require(amount.compareTo(allocated.getOrDefault(id, BigDecimal.ZERO)) == 0,
                "精确库存" + id + "完整当量与全部真实位置分配不守恒；不能改旧量、造分数板或假报废位"));
        if (unstocked > 0) {
            limitations.add("现场声明存在未库存化实物；必须另有真实生产/不合格来源和隔离库存化事务，不能增加PASS或借合格库存");
        }
        if (unknown > 0) {
            limitations.add("存在尚未明确的实际位置，不能异人确认或默认GOOD/MISSING");
        }
        limitations.add("当前位置只读预检未保存永久原账，未完成异人确认、位置余额和全部质量/下游资格");
        var result = new MesPhysicalPositionSurveyDTO.Preview();
        result.setVersion(MesPhysicalPositionSurveyCodec.VERSION);
        result.setSurveySnapshot(rawJson);
        result.setSurveyFingerprint(codec.fingerprint(MesPhysicalPositionSurveyCodec.VERSION, rawJson));
        result.setTenantId(bundle.getTenantId());
        result.setFactoryCode(bundle.getFactoryCode());
        result.setActorUserId(bundle.getActorUserId());
        result.setActorCode(bundle.getActorCode());
        result.setCanonicalIdentifier(canonical);
        result.setPhysicalObjectCount("1");
        result.setSourceBundle(bundle);
        result.setStandardId(standardId);
        result.setStandardFingerprint(standardSha);
        result.setLayoutCode(layoutCode);
        result.setPositions(body.getPositions());
        var equivalentTexts = new TreeMap<String, String>();
        equivalents.forEach((id, amount) -> equivalentTexts.put(id, codec.amount(amount)));
        result.setSourceEquivalents(equivalentTexts);
        result.setStockedPositionCount(Integer.toString(stocked));
        result.setUnstockedPositionCount(Integer.toString(unstocked));
        result.setMissingPositionCount(Integer.toString(missing));
        result.setUnknownPositionCount(Integer.toString(unknown));
        result.setLimitations(List.copyOf(limitations));
        result.setPersisted(false);
        result.setExecutionAvailable(false);
        result.setMessage("仅本次真实来源锁内的候选母件位置预检；现场存在状态仍是本人声明，未登记/异人确认/库存化/占用或转换，不是实物或质量放行凭证");
        var evidence = new TreeMap<String, Object>();
        evidence.put("version", MesPhysicalPositionSurveyCodec.EVIDENCE_VERSION);
        evidence.put("surveySnapshot", rawJson);
        evidence.put("surveyFingerprint", result.getSurveyFingerprint());
        evidence.put("sourceBundleSnapshot", bundle.getBundleSnapshot());
        evidence.put("sourceBundleFingerprint", bundle.getBundleFingerprint());
        evidence.put("canonicalIdentifier", canonical);
        evidence.put("physicalObjectCount", "1");
        evidence.put("standardId", standardId);
        evidence.put("standardFingerprint", standardSha);
        evidence.put("layoutCode", layoutCode);
        evidence.put("positions", body.getPositions());
        evidence.put("sourceEquivalents", equivalentTexts);
        evidence.put("limitations", result.getLimitations());
        String evidenceRaw = codec.snapshot(evidence);
        codec.objectDocument(evidenceRaw, MesPhysicalPositionSurveyCodec.MAX_EVIDENCE);
        result.setEvidenceSnapshot(evidenceRaw);
        result.setEvidenceFingerprint(codec.fingerprint(MesPhysicalPositionSurveyCodec.EVIDENCE_VERSION, evidenceRaw));
        return result;
    }
}
