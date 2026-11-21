package com.kitex.mom.wms.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.data.orgFilter.FactoryContextHolder;
import com.kitex.common.data.tenant.TenantContextHolder;
import com.kitex.mom.common.identifier.IdentifierCodeTemplate;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.entity.WmsIdentifierPolicyEntity;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsBatchCodeSequenceMapper;
import com.kitex.mom.wms.mapper.WmsIdentifierPolicyMapper;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsSerialCodeSequenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 物料主数据定义最低标识要求，WMS入库类型规则只能加强校验。
 */
@Service
@RequiredArgsConstructor
public class WmsIdentifierPolicyService {
    private static final String ANY = "*";
    private final WmsIdentifierPolicyMapper mapper;
    private final WmsLiveStockMapper stockMapper;
    private final RemoteMaterialService materialService;
    private final WmsSerialIdentityLock serialIdentityLock;
    private final WmsBatchCodeSequenceMapper batchSequences;
    private final WmsSerialCodeSequenceMapper serialSequences;

    @Transactional(rollbackFor = Exception.class)
    public WmsIdentifierPolicyEntity savePolicy(WmsIdentifierPolicyEntity policy) {
        if (policy == null) {
            throw new CheckedException("标识规则不能为空");
        }
        Scope scope = scope();
        String billType = StrUtil.trim(policy.getBillType());
        String materialCode = StrUtil.trim(policy.getMaterialCode());
        if (StrUtil.isBlank(billType) || StrUtil.isBlank(materialCode)
                || (!ANY.equals(billType) && !InBillTypeEnum.isValid(billType)
                && !OtherInBillTypeEnum.isValid(billType))
                || billType.length() > 50 || materialCode.length() > 64
                || policy.getBatchRequired() == null || policy.getBarcodeRequired() == null
                || policy.getSerialManaged() == null || policy.getStatus() == null
                || StrUtil.length(policy.getRemark()) > 500) {
            throw new CheckedException("标识规则的范围、必填开关或启用状态无效");
        }
        if (Boolean.TRUE.equals(policy.getSerialManaged()) && !Boolean.TRUE.equals(policy.getBarcodeRequired())) {
            throw new CheckedException("单件管理必须要求条码或序列号");
        }
        if (Boolean.TRUE.equals(policy.getAutoBatchOnMissing()) && !Boolean.TRUE.equals(policy.getBatchRequired())) {
            throw new CheckedException("自动生成批次时必须要求批次必填");
        }
        if (Boolean.TRUE.equals(policy.getAutoSerialOnMissing())
                && (!Boolean.TRUE.equals(policy.getSerialManaged())
                || !Boolean.TRUE.equals(policy.getBarcodeRequired()))) {
            throw new CheckedException("自动生成单件条码时必须启用单件管理和条码必填");
        }
        policy.setAutoBatchOnMissing(Boolean.TRUE.equals(policy.getAutoBatchOnMissing()));
        policy.setAutoSerialOnMissing(Boolean.TRUE.equals(policy.getAutoSerialOnMissing()));
        validatePattern(policy.getBatchPattern());
        validatePattern(policy.getBarcodePattern());
        policy.setBillType(billType);
        policy.setMaterialCode(materialCode);
        policy.setBatchPattern(StrUtil.emptyToNull(StrUtil.trim(policy.getBatchPattern())));
        policy.setBarcodePattern(StrUtil.emptyToNull(StrUtil.trim(policy.getBarcodePattern())));
        policy.setFactoryCode(scope.factory());
        policy.setTenantId(scope.tenant());
        policy.setDelFlag("0");
        // 通配规则无法预检每个物料的模板，执行入库时仍逐条校验。
        if (Boolean.TRUE.equals(policy.getStatus()) && !ANY.equals(materialCode)) {
            MdmMaterialEntity material = material(materialCode);
            if (Boolean.TRUE.equals(policy.getAutoBatchOnMissing())) {
                validatedTemplate(material.getBatchCodeTemplate(), "批次", materialCode);
            }
            if (Boolean.TRUE.equals(policy.getAutoSerialOnMissing())) {
                validatedTemplate(material.getSerialCodeTemplate(), "单件条码", materialCode);
            }
        }
        if (policy.getId() != null) {
            WmsIdentifierPolicyEntity existing = mapper.selectById(policy.getId());
            if (existing == null || !Objects.equals(scope.tenant(), existing.getTenantId())
                    || !Objects.equals(scope.factory(), existing.getFactoryCode())) {
                throw new CheckedException("标识规则不存在或超出当前组织范围");
            }
        }
        Long duplicates = mapper.selectCount(new LambdaQueryWrapper<WmsIdentifierPolicyEntity>()
                .eq(WmsIdentifierPolicyEntity::getTenantId, scope.tenant())
                .eq(WmsIdentifierPolicyEntity::getFactoryCode, scope.factory())
                .eq(WmsIdentifierPolicyEntity::getBillType, billType)
                .eq(WmsIdentifierPolicyEntity::getMaterialCode, materialCode)
                .eq(WmsIdentifierPolicyEntity::getStatus, true)
                .ne(policy.getId() != null, WmsIdentifierPolicyEntity::getId, policy.getId()));
        if (Boolean.TRUE.equals(policy.getStatus()) && duplicates > 0) {
            throw new CheckedException("相同业务类型和物料已有启用的标识规则");
        }
        int changed;
        if (policy.getId() == null) {
            changed = mapper.insert(policy);
        } else {
            changed = mapper.update(null, new LambdaUpdateWrapper<WmsIdentifierPolicyEntity>()
                    .eq(WmsIdentifierPolicyEntity::getId, policy.getId())
                    .eq(WmsIdentifierPolicyEntity::getTenantId, scope.tenant())
                    .eq(WmsIdentifierPolicyEntity::getFactoryCode, scope.factory())
                    .set(WmsIdentifierPolicyEntity::getBillType, billType)
                    .set(WmsIdentifierPolicyEntity::getMaterialCode, materialCode)
                    .set(WmsIdentifierPolicyEntity::getBatchRequired, policy.getBatchRequired())
                    .set(WmsIdentifierPolicyEntity::getBatchPattern, policy.getBatchPattern())
                    .set(WmsIdentifierPolicyEntity::getAutoBatchOnMissing, policy.getAutoBatchOnMissing())
                    .set(WmsIdentifierPolicyEntity::getBarcodeRequired, policy.getBarcodeRequired())
                    .set(WmsIdentifierPolicyEntity::getBarcodePattern, policy.getBarcodePattern())
                    .set(WmsIdentifierPolicyEntity::getAutoSerialOnMissing, policy.getAutoSerialOnMissing())
                    .set(WmsIdentifierPolicyEntity::getSerialManaged, policy.getSerialManaged())
                    .set(WmsIdentifierPolicyEntity::getStatus, policy.getStatus())
                    .set(WmsIdentifierPolicyEntity::getRemark, policy.getRemark()));
        }
        if (changed != 1) {
            throw new CheckedException("标识规则保存未确认");
        }
        return policy;
    }

    public List<WmsIdentifierPolicyEntity> listPolicies() {
        Scope scope = scope();
        return mapper.selectList(new LambdaQueryWrapper<WmsIdentifierPolicyEntity>()
                .eq(WmsIdentifierPolicyEntity::getTenantId, scope.tenant())
                .eq(WmsIdentifierPolicyEntity::getFactoryCode, scope.factory())
                .orderByAsc(WmsIdentifierPolicyEntity::getBillType)
                .orderByAsc(WmsIdentifierPolicyEntity::getMaterialCode));
    }

    public WmsIdentifierPolicyEntity resolve(String billType, String materialCode) {
        Scope scope = scope();
        if (StrUtil.isBlank(billType) || StrUtil.isBlank(materialCode)) {
            throw new CheckedException("标识规则解析缺少业务类型或物料编码");
        }
        String bill = billType.trim();
        String material = materialCode.trim();
        return mapper.selectList(new LambdaQueryWrapper<WmsIdentifierPolicyEntity>()
                        .eq(WmsIdentifierPolicyEntity::getTenantId, scope.tenant())
                        .eq(WmsIdentifierPolicyEntity::getFactoryCode, scope.factory())
                        .eq(WmsIdentifierPolicyEntity::getStatus, true)
                        .in(WmsIdentifierPolicyEntity::getBillType, List.of(bill, ANY))
                        .in(WmsIdentifierPolicyEntity::getMaterialCode, List.of(material, ANY)))
                .stream().min(Comparator.comparingInt(item -> priority(item, bill, material))).orElse(null);
    }

    /** 按当前工厂物料和入库业务类型核对明细；保存草稿时物料追溯要求受当前事务保护。 */
    public void validateInbound(String billType, WmsInBillDetailEntity detail) {
        validateInbound(billType, detail, false);
    }

    public WmsIdentifierPolicyEntity resolveEffective(String billType, String materialCode) {
        MdmMaterialEntity material = material(materialCode);
        WmsIdentifierPolicyEntity configured = resolve(billType, materialCode);
        boolean batchManaged = Boolean.TRUE.equals(material.getBatchManaged());
        boolean serialManaged = Boolean.TRUE.equals(material.getSerialManaged());
        if (configured == null && !batchManaged && !serialManaged) {
            return null;
        }
        WmsIdentifierPolicyEntity effective = new WmsIdentifierPolicyEntity();
        if (configured != null) {
            BeanUtil.copyProperties(configured, effective);
        }
        effective.setBatchRequired(batchManaged
                || configured != null && Boolean.TRUE.equals(configured.getBatchRequired()));
        effective.setAutoBatchOnMissing(configured != null
                && Boolean.TRUE.equals(configured.getAutoBatchOnMissing()));
        effective.setSerialManaged(serialManaged
                || configured != null && Boolean.TRUE.equals(configured.getSerialManaged()));
        effective.setAutoSerialOnMissing(configured != null
                && Boolean.TRUE.equals(configured.getAutoSerialOnMissing()));
        effective.setBarcodeRequired(Boolean.TRUE.equals(effective.getSerialManaged())
                || configured != null && Boolean.TRUE.equals(configured.getBarcodeRequired()));
        return effective;
    }

    /**
     * 在实际库存事务中核对批次和条码。返回值写入本次实物的单件标记，供后续拣配和移动使用。
     * 同工厂同条码的冲突、数量与追溯要求不符均中止本次记账。
     */
    public boolean validateInboundForPosting(String billType, WmsInBillDetailEntity detail) {
        return validateInbound(billType, detail, true);
    }

    /** 只在新建入库明细时填充空标识；后续审核使用已保存的值。 */
    public void fillMissingIdentifiers(String billType, WmsInBillDetailEntity detail) {
        if (detail == null || StrUtil.isBlank(detail.getMatCode())) {
            return;
        }
        WmsIdentifierPolicyEntity policy = resolve(billType, detail.getMatCode());
        if (policy == null) {
            return;
        }
        boolean createBatch = StrUtil.isBlank(detail.getMatBatch())
                && Boolean.TRUE.equals(policy.getAutoBatchOnMissing());
        boolean createSerial = StrUtil.isBlank(detail.getMatBarcode())
                && Boolean.TRUE.equals(policy.getAutoSerialOnMissing());
        if (!createBatch && !createSerial) {
            return;
        }
        MdmMaterialEntity material = material(detail.getMatCode());
        if (createSerial && (!Boolean.TRUE.equals(policy.getSerialManaged())
                || detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
                || detail.getMatNum() != 1d)) {
            throw new CheckedException("自动生成单件条码要求单件管理且入库明细数量为1");
        }
        String batchTemplate = createBatch
                ? validatedTemplate(material.getBatchCodeTemplate(), "批次", detail.getMatCode()) : null;
        String serialTemplate = createSerial
                ? validatedTemplate(material.getSerialCodeTemplate(), "单件条码", detail.getMatCode()) : null;
        Scope scope = scope();
        var context = new IdentifierCodeTemplate.Context(scope.tenant().toString(), scope.factory(),
                material.getMaterialCode(), material.getMaterialType(), material.getMaterialAttribute(),
                material.getGroupCode());
        LocalDateTime now = LocalDateTime.now();
        if (createBatch) {
            detail.setMatBatch(render(batchTemplate, context, now, batchSequences.nextValue(),
                    "批次", detail.getMatCode()));
        }
        if (createSerial) {
            detail.setMatBarcode(render(serialTemplate, context, now, serialSequences.nextValue(),
                    "单件条码", detail.getMatCode()));
        }
    }

    private String validatedTemplate(String raw, String kind, String materialCode) {
        String template = StrUtil.trim(raw);
        if (StrUtil.isBlank(template)) {
            throw new CheckedException("物料[" + materialCode + "]未配置" + kind + "编码模板");
        }
        try {
            IdentifierCodeTemplate.validate(template);
        } catch (IllegalArgumentException exception) {
            throw new CheckedException("物料[" + materialCode + "]" + kind + "编码配置无效: "
                    + exception.getMessage());
        }
        return template;
    }

    private String render(String template, IdentifierCodeTemplate.Context context, LocalDateTime now,
                          Long sequence, String kind, String materialCode) {
        if (sequence == null) {
            throw new CheckedException(kind + "流水号分配失败");
        }
        try {
            return IdentifierCodeTemplate.render(template, context, now, sequence);
        } catch (IllegalArgumentException exception) {
            throw new CheckedException("物料[" + materialCode + "]" + kind + "编码配置无效: "
                    + exception.getMessage());
        }
    }

    private boolean validateInbound(String billType, WmsInBillDetailEntity detail, boolean posting) {
        if (detail == null || StrUtil.isBlank(detail.getMatCode())) {
            throw new CheckedException("入库明细缺少物料编码");
        }
        // 只读预览仍可独立调用；草稿写入及正式记账在业务事务内持锁至提交。
        if (posting || TransactionSynchronizationManager.isActualTransactionActive()) {
            Scope scope = scope();
            serialIdentityLock.lockMaterialPolicy(scope.tenant(), scope.factory(), detail.getMatCode());
        }
        MdmMaterialEntity material = material(detail.getMatCode());
        WmsIdentifierPolicyEntity policy = resolve(billType, detail.getMatCode());
        String batch = StrUtil.trim(detail.getMatBatch());
        String barcode = StrUtil.trim(detail.getMatBarcode());
        detail.setMatBatch(StrUtil.emptyToNull(batch));
        detail.setMatBarcode(StrUtil.emptyToNull(barcode));
        boolean batchRequired = Boolean.TRUE.equals(material.getBatchManaged())
                || policy != null && Boolean.TRUE.equals(policy.getBatchRequired());
        boolean serialManaged = Boolean.TRUE.equals(material.getSerialManaged())
                || policy != null && Boolean.TRUE.equals(policy.getSerialManaged());
        boolean barcodeRequired = serialManaged
                || policy != null && Boolean.TRUE.equals(policy.getBarcodeRequired());
        if (batchRequired && StrUtil.isBlank(batch)) {
            throw new CheckedException("物料[" + detail.getMatCode() + "]必须填写批次");
        }
        if (barcodeRequired && StrUtil.isBlank(barcode)) {
            throw new CheckedException("物料[" + detail.getMatCode() + "]必须填写条码或序列号");
        }
        if (policy != null && StrUtil.isNotBlank(batch) && StrUtil.isNotBlank(policy.getBatchPattern())
                && !Pattern.matches(policy.getBatchPattern(), batch)) {
            throw new CheckedException("物料[" + detail.getMatCode() + "]批次不符合配置规则");
        }
        if (policy != null && StrUtil.isNotBlank(barcode) && StrUtil.isNotBlank(policy.getBarcodePattern())
                && !Pattern.matches(policy.getBarcodePattern(), barcode)) {
            throw new CheckedException("物料[" + detail.getMatCode() + "]条码不符合配置规则");
        }
        if (serialManaged) {
            if (detail.getMatNum() == null || !Double.isFinite(detail.getMatNum())
                    || detail.getMatNum() != 1d) {
                throw new CheckedException("单件管理物料每条入库明细数量必须为1");
            }
        }
        if (StrUtil.isNotBlank(barcode)) {
            Scope scope = scope();
            if (posting) {
                serialIdentityLock.lock(scope.tenant(), scope.factory(), barcode);
            }
            if (stockMapper.hasConflictingBarcode(scope.tenant(), scope.factory(), barcode, serialManaged)) {
                throw new CheckedException("条码或序列号[" + barcode + "]在仓库或拣货区仍有结存，请核对原件");
            }
        }
        return serialManaged;
    }

    private MdmMaterialEntity material(String materialCode) {
        Scope scope = scope();
        R<MdmMaterialEntity> response = materialService.getDetails(materialCode);
        MdmMaterialEntity material = response == null || !response.isOk() ? null : response.getData();
        if (material == null
                || !Objects.equals(scope.tenant(), material.getTenantId())
                || !Objects.equals(scope.factory(), material.getFactoryCode())
                || !Objects.equals(materialCode, material.getMaterialCode())) {
            throw new CheckedException("物料[" + materialCode + "]不存在或不属于当前租户工厂");
        }
        return material;
    }

    private int priority(WmsIdentifierPolicyEntity item, String bill, String material) {
        int billRank = bill.equals(item.getBillType()) ? 0 : 2;
        int materialRank = material.equals(item.getMaterialCode()) ? 0 : 1;
        return billRank + materialRank;
    }

    private void validatePattern(String regex) {
        if (StrUtil.isBlank(regex)) {
            return;
        }
        if (regex.length() > 128) {
            throw new CheckedException("批次或条码正则规则最长128字符");
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException exception) {
            throw new CheckedException("批次或条码正则规则无效");
        }
    }

    private Scope scope() {
        Long tenant = TenantContextHolder.getTenantId();
        String factory = FactoryContextHolder.getFactoryCode();
        if (tenant == null || StrUtil.isBlank(factory)
                || Boolean.TRUE.equals(TenantContextHolder.getTenantSkip())
                || Boolean.TRUE.equals(FactoryContextHolder.getFactorySkip())) {
            throw new CheckedException("标识规则必须具有租户和工厂上下文");
        }
        return new Scope(tenant, factory);
    }

    private record Scope(Long tenant, String factory) {
    }
}
