package com.kitex.mom.pms.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmSupplierEntity;
import com.kitex.mom.mdm.feign.RemoteSupplierService;
import com.kitex.mom.pms.agent.dto.PmsSupplierQualitySourceDTO;
import com.kitex.mom.pms.entity.PmsSupplierMatEntity;
import com.kitex.mom.pms.mapper.PmsSupplierMatMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 冻结供应商来料质量改善场景的供应商—物料对象。
 *
 * <p>PMS供货物料主键是唯一入口，供应商身份和资格必须由MDM精确回执；
 * 不接受按供应商名称、物料名称或历史检验记录反向猜测对象。</p>
 */
@Service
@RequiredArgsConstructor
public class PmsSupplierQualitySourceQueryService {

    public static final String TOOL_CODE = "PMS_SUPPLIER_QUALITY_SOURCE_QUERY@1";

    private final PmsAgentFactoryAccessService factoryAccess;
    private final PmsSupplierMatMapper supplierMatMapper;
    private final RemoteSupplierService supplierService;
    private final ObjectMapper objectMapper;

    public PmsSupplierQualitySourceDTO.Evidence query(PmsSupplierQualitySourceDTO.Query query) {
        if (query == null || !StringUtils.hasText(query.getSupplierMaterialId())) {
            throw new CheckedException("供应商质量改善必须指定供货物料主键");
        }
        PmsAgentFactoryAccessService.Scope scope = factoryAccess.requireRead(query.getFactoryCode());
        Long supplierMaterialId = positiveId(query.getSupplierMaterialId());
        List<PmsSupplierMatEntity> rows = supplierMatMapper.selectList(
                new LambdaQueryWrapper<PmsSupplierMatEntity>()
                        .eq(PmsSupplierMatEntity::getTenantId, scope.tenantId())
                        .eq(PmsSupplierMatEntity::getId, supplierMaterialId)
                        .eq(PmsSupplierMatEntity::getDelFlag, "0")
                        .last("LIMIT 2"));
        if (rows.size() != 1) {
            throw new CheckedException(rows.isEmpty()
                    ? "当前租户没有该供应商供货物料" : "供应商供货物料主键不唯一，禁止Agent取证");
        }
        PmsSupplierMatEntity source = rows.get(0);
        if (source.getSupplierId() == null) {
            throw new CheckedException("供应商供货物料缺少MDM供应商主键");
        }
        MdmSupplierEntity supplier = supplier(source.getSupplierId(), scope.factoryCode());
        List<String> gaps = gaps(source, supplier);
        List<String> refs = List.of("PMS:pms_supplier_mat:" + source.getId(),
                "MDM:mdm_supplier:" + supplier.getId());
        PmsSupplierQualitySourceDTO.Evidence evidence = new PmsSupplierQualitySourceDTO.Evidence()
                .setToolCode(TOOL_CODE)
                .setMetricDefinition("以PMS供应商供货物料主键冻结分析对象，并由MDM供应商主键、当前工厂、审核、启用和供货资格交叉确认；评级、评分和免检标志只按当前PMS记录展示，不由Agent修改。")
                .setFactoryCode(scope.factoryCode()).setSupplierMaterialId(String.valueOf(source.getId()))
                .setSupplierId(String.valueOf(supplier.getId())).setSupplierCode(supplier.getSupplierCode())
                .setSupplierName(supplier.getSupplierName()).setSupplierClassCode(supplier.getSupplierClassCode())
                .setSupplierClassName(supplier.getSupplierClassName()).setSupplierEnabled(supplier.getStatus())
                .setSupplierCheckStatus(supplier.getCheckStatus()).setSupplierDeliverable(supplier.getIsDeliver())
                .setMaterialCode(source.getMatCode()).setMaterialName(source.getMatName())
                .setMaterialSpec(source.getMatSpec()).setMaterialUnit(source.getMatUnit())
                .setInspectionExempt(source.getIsQuality()).setCurrentRating(source.getRating())
                .setCurrentScore(source.getScore()).setObservedAt(LocalDateTime.now())
                .setDataGaps(gaps)
                .setLimitations(List.of("供应商当前资格不代表历史每个来料批次均合格",
                        "评分、评级、免检和供应商状态调整必须回到PMS/MDM原业务流程"))
                .setEvidenceRefs(refs);
        evidence.setFactFingerprint(fingerprint(evidence));
        return evidence;
    }

    private MdmSupplierEntity supplier(Long supplierId, String factoryCode) {
        MdmSupplierEntity criteria = new MdmSupplierEntity();
        criteria.setId(supplierId);
        R<MdmSupplierEntity> response;
        try {
            response = supplierService.getDetails(criteria);
        } catch (Exception failure) {
            throw new CheckedException("MDM供应商服务不可用，不能冻结质量改善对象");
        }
        MdmSupplierEntity supplier = response == null || !response.isOk() ? null : response.getData();
        if (supplier == null || !Objects.equals(supplierId, supplier.getId())
                || !Objects.equals(factoryCode, supplier.getFactoryCode())) {
            throw new CheckedException("MDM未返回当前工厂唯一供应商，禁止跨工厂取证");
        }
        return supplier;
    }

    private List<String> gaps(PmsSupplierMatEntity source, MdmSupplierEntity supplier) {
        List<String> gaps = new ArrayList<>();
        if (!StringUtils.hasText(supplier.getSupplierCode()) || !StringUtils.hasText(supplier.getSupplierName())) {
            gaps.add("MDM供应商缺少编码或名称");
        }
        if (!StringUtils.hasText(source.getMatCode()) || !StringUtils.hasText(source.getMatUnit())) {
            gaps.add("PMS供货物料缺少物料编码或计量单位，不能计算质量数量指标");
        }
        if (!Boolean.TRUE.equals(supplier.getStatus())) gaps.add("MDM供应商当前未启用");
        if (!"1".equals(supplier.getCheckStatus())) gaps.add("MDM供应商当前未审核通过");
        if (!Boolean.TRUE.equals(supplier.getIsDeliver())) gaps.add("MDM供应商当前未被允许供货");
        return gaps;
    }

    private String fingerprint(PmsSupplierQualitySourceDTO.Evidence evidence) {
        try {
            String facts = objectMapper.writeValueAsString(List.of(evidence.getFactoryCode(),
                    evidence.getSupplierMaterialId(), evidence.getSupplierId(),
                    Objects.toString(evidence.getSupplierCode(), ""),
                    Objects.toString(evidence.getMaterialCode(), ""),
                    Objects.toString(evidence.getMaterialUnit(), ""), evidence.getSupplierEnabled(),
                    Objects.toString(evidence.getSupplierCheckStatus(), ""), evidence.getSupplierDeliverable(),
                    evidence.getInspectionExempt(), Objects.toString(evidence.getCurrentRating(), ""),
                    evidence.getCurrentScore(), evidence.getDataGaps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new CheckedException("供应商供货物料事实无法生成完整指纹");
        }
    }

    private Long positiveId(String value) {
        try {
            if (!value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException invalid) {
            throw new CheckedException("供应商供货物料主键无效");
        }
    }
}
