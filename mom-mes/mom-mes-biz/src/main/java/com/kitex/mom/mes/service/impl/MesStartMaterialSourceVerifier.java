package com.kitex.mom.mes.service.impl;

import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mes.dto.MesStartMaterialBindingDTO.Source;
import com.kitex.mom.mes.entity.MesTaskArrangeEntity;
import com.kitex.mom.mes.entity.MesTaskEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedBirthEntity;
import com.kitex.mom.mes.entity.MesWarehouseFeedEntity;
import com.kitex.mom.mes.service.MesWarehouseFeedBirthService;
import com.kitex.mom.mes.util.MesMaterialSubstituteResolver;
import com.kitex.mom.mes.util.MesTaskUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Objects;

import static com.kitex.mom.mes.service.impl.MesPhysicalTransformCodec.require;

/**
 * 首次绑定和暂停追加共用的来源资格；旧成员允许耗尽但必须保留已知余额和原身份。
 */
@Component
@RequiredArgsConstructor
public class MesStartMaterialSourceVerifier {
    private final MesWarehouseFeedBirthService births;
    private final RemoteMaterialService materials;
    private final MesStartMaterialBindingCodec codec;
    private final MesPhysicalTransformCodec physical;

    public boolean eligible(MesWarehouseFeedEntity feed) {
        return Boolean.FALSE.equals(feed.getIsLock()) && feed.getFeedNum() != null && feed.getUsedNum() != null
                && feed.getFeedNum().compareTo(feed.getUsedNum()) > 0
                && (feed.getMatValidEndDate() == null || !feed.getMatValidEndDate().isBefore(LocalDate.now()));
    }

    public Source source(Long tenant, String factory, MesTaskArrangeEntity arrange, MesTaskEntity task,
                         MesWarehouseFeedEntity feed, boolean newlySelected) {
        require(feed != null && feed.getId() != null && Objects.equals(feed.getTenantId(), tenant)
                && Objects.equals(feed.getFactoryCode(), factory) && Objects.equals(feed.getTaskCode(), arrange.getTaskCode())
                && Objects.equals(feed.getProduceTaskCode(), arrange.getProduceTaskCode()) && Objects.equals(feed.getArrangeCode(), arrange.getCode())
                && "0".equals(feed.getDelFlag()) && feed.getIsLock() != null, "投料来源归属、质量锁定或效期不合格");
        require(feed.getFeedNum() != null && feed.getUsedNum() != null && feed.getUsedNum().signum() >= 0
                && feed.getFeedNum().signum() > 0 && feed.getFeedNum().compareTo(feed.getUsedNum()) >= 0, "原投料总量或已用量未知、无余额或无效");
        require(!newlySelected || eligible(feed), "新增投料来源无余额、质量锁定或已过期");
        String requirement = feed.getRequirementMatCode();
        var route = MesTaskUtils.getCurrentProcess(task.getProcessRouteSnapshot(), arrange.getRouteCode(), arrange.getSourceRouteNodeCode());
        require(route.getMdmProcessProcedureMatList() != null && route.getMdmProcessProcedureMatList().stream()
                .filter(r -> r != null && Objects.equals(r.getMaterialCode(), requirement)).count() == 1, "所选来源不是原工序唯一实际需求");
        // 耗尽、锁定或过期旧成员只读其永久身份；当前主数据不能抹去历史来源。
        if (newlySelected || eligible(feed)) {
            var policy = MesMaterialSubstituteResolver.resolvePolicy(task.getBomSnapshot(), requirement);
            require(feed.getSubstituteRatio() != null && policy.candidates().stream().anyMatch(c ->
                    Objects.equals(c.actualMatCode(), feed.getMatCode()) && c.ratio().compareTo(feed.getSubstituteRatio()) == 0
                            && Objects.equals(c.substitute(), feed.getSubstituteFlag())), "所选来源替代身份或比例与原BOM不同");
            var measure = measure(tenant, factory, feed.getMatCode());
            require(Objects.equals(feed.getMatUnit(), measure.getBasicUnit()), "所选投料原单位与实际物料不一致");
        }
        var evidence = births.evidence(feed.getId());
        require(evidence != null && "RECORDED".equals(evidence.status()) && evidence.birth() != null && !evidence.sources().isEmpty(), "所选投料缺少完整RECORDED出生证明");
        var birth = evidence.birth();
        require(Objects.equals(birth.getTenantId(), tenant) && Objects.equals(birth.getFactoryCode(), factory)
                && Objects.equals(birth.getFeedId(), feed.getId()) && Objects.equals(birth.getTaskId(), task.getId())
                && birth.getId() != null && birth.getId() > 0 && Objects.equals(birth.getArrangeId(), arrange.getId())
                && Objects.equals(birth.getSourceCount(), evidence.sources().size()), "所选出生原证据跨原派工或不完整");
        codec.hash(birth.getEvidenceFingerprint());
        assertBirthIdentity(feed, birth);
        return new Source(feed.getId().toString(), birth.getId().toString(), birth.getEvidenceFingerprint(), requirement,
                feed.getMatCode(), feed.getMatBatch(), feed.getMatBarcode(), feed.getMatUnit(), physical.amount(feed.getSubstituteRatio()),
                physical.amount(feed.getFeedNum()), physical.amount(feed.getUsedNum()));
    }

    /**
     * 固定身份投影避免未来新增空列影响旧出生；原量、原单位必须对应同一真实feed。
     */
    private void assertBirthIdentity(MesWarehouseFeedEntity feed, MesWarehouseFeedBirthEntity birth) {
        require(birth.getQuantity() != null && birth.getQuantity().compareTo(feed.getFeedNum()) == 0
                && Objects.equals(birth.getUnit(), feed.getMatUnit()), "原出生数量或单位与所选投料不同");
        var original = physical.objectDocument(birth.getFeedSnapshot(), 8 * 1024 * 1024);
        var identity = new LinkedHashMap<String, Object>();
        identity.put("id", feed.getId());
        identity.put("tenant_id", feed.getTenantId());
        identity.put("factory_code", feed.getFactoryCode());
        identity.put("task_code", feed.getTaskCode());
        identity.put("produce_task_code", feed.getProduceTaskCode());
        identity.put("arrange_code", feed.getArrangeCode());
        identity.put("mat_code", feed.getMatCode());
        identity.put("requirement_mat_code", feed.getRequirementMatCode());
        identity.put("mat_batch", feed.getMatBatch());
        identity.put("mat_barcode", feed.getMatBarcode());
        identity.put("mat_unit", feed.getMatUnit());
        identity.put("feed_num", feed.getFeedNum());
        identity.put("substitute_ratio", feed.getSubstituteRatio());
        identity.put("substitute_flag", feed.getSubstituteFlag());
        for (var entry : identity.entrySet()) {
            var value = original.get(entry.getKey());
            require(value != null, "原出生缺少投料固定身份字段");
            if (entry.getValue() == null) {
                require(value.isNull(), "原出生投料身份不同");
            } else if (entry.getValue() instanceof BigDecimal amount) {
                require(value.isNumber() && value.decimalValue().compareTo(amount) == 0, "原出生原数量或比例不同");
            } else if (entry.getValue() instanceof Boolean flag) {
                require(value.isBoolean() && value.booleanValue() == flag, "原出生替代身份不同");
            } else {
                require(Objects.equals(value.asText(), entry.getValue().toString()), "原出生投料固定身份不同");
            }
        }
    }

    public MdmMaterialMeasureDTO measure(Long tenant, String factory, String code) {
        var response = materials.productionMeasure(code);
        var material = response == null ? null : response.getData();
        require(response != null && response.isOk() && material != null && Boolean.TRUE.equals(material.getEnabled())
                && Objects.equals(material.getTenantId(), tenant.toString()) && Objects.equals(material.getFactoryCode(), factory)
                && Objects.equals(material.getMaterialCode(), code), "实际物料启用、组织或原计量不可核验");
        physical.code(material.getBasicUnit(), "实际基本单位", 100);
        physical.id(material.getMaterialId());
        return material;
    }
}
