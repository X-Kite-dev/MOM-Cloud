package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.entity.MesInspectionRuleMatEntity;
import com.kitex.mom.mes.entity.MesInspectionGenRecordEntity;
import com.kitex.mom.mes.enums.QualityGateModeEnum;
import com.kitex.mom.mes.mapper.MesInspectionRuleMapper;
import com.kitex.mom.mes.service.MesInspectionRuleMatService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import com.kitex.mom.mes.service.MesInspectionGenRecordService;
import com.kitex.mom.qms.enums.ProcedureLabelTypeEnum;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 质检任务生成规则
 *
 * @author kitex
 * @since 2024-02-06
 */
@Service
@AllArgsConstructor
public class MesInspectionRuleServiceImpl extends ServiceImpl<MesInspectionRuleMapper, MesInspectionRuleEntity> implements MesInspectionRuleService {

    private final MesInspectionRuleMatService ruleMatService;
    private final MesInspectionGenRecordService genRecordService;
    private final RemoteSeqConfigService seqConfigService;

    /**
     * 保存
     *
     * @param rule
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveData(MesInspectionRuleEntity rule) {
        if (rule == null || StrUtil.isBlank(rule.getName())) {
            throw new CheckedException("质检规则名称不能为空");
        }
        if (!"0".equals(rule.getStatus()) && !"1".equals(rule.getStatus())) {
            throw new CheckedException("质检规则状态只能是启用或停用");
        }
        rule.setGateMode(QualityGateModeEnum.fromCode(rule.getGateMode()).name());
        rule.setInspectionTypes(normalizeInspectionTypes(rule.getInspectionTypes()));
        if (rule.getInspectionTypes().contains(ProcedureLabelTypeEnum.MIDDLE.name())) {
            if (!"HOUR".equals(rule.getMethod()) && !"SHIFT".equals(rule.getMethod())) {
                throw new CheckedException("中检触发方式只能是按小时或按班次");
            }
            if (rule.getNum() == null || rule.getNum().signum() <= 0) {
                throw new CheckedException("中检时间或班次间隔必须大于0");
            }
        }
        if (CollUtil.isEmpty(rule.getMatList())) {
            throw new CheckedException("关联产品为空，保存失败");
        }
        Map<String, MesInspectionRuleMatEntity> distinctMats = rule.getMatList().stream()
                .filter(Objects::nonNull).filter(item -> StrUtil.isNotBlank(item.getMatCode()))
                .collect(Collectors.toMap(item -> item.getMatCode().trim(), item -> {
                    item.setMatCode(item.getMatCode().trim());
                    return item;
                }, (left, right) -> left, LinkedHashMap::new));
        if (distinctMats.size() != rule.getMatList().size()) {
            throw new CheckedException("关联产品存在空编码或重复产品");
        }
        MesInspectionRuleEntity persisted = rule.getId() == null ? null : this.getById(rule.getId());
        if (rule.getId() != null && persisted == null) {
            throw new CheckedException("质检规则不存在或已删除");
        }
        if (persisted == null) {
            rule.setCode(seqConfigService.create("mes_inspection_rule_code").getData());
        } else {
            rule.setCode(persisted.getCode());
        }
        List<String> matCodes = new ArrayList<>(distinctMats.keySet());
        long existCount = ruleMatService.count(new LambdaQueryWrapper<MesInspectionRuleMatEntity>()
                .in(MesInspectionRuleMatEntity::getMatCode, matCodes)
                .ne(StrUtil.isNotBlank(rule.getCode()), MesInspectionRuleMatEntity::getRuleCode, rule.getCode()));
        if (existCount > 0) {
            throw new CheckedException("关联产品中存在已设置的其他质检规则，请勿重复保存");
        }
        rule.setMatNames(distinctMats.values().stream().map(MesInspectionRuleMatEntity::getMatName)
                .filter(StrUtil::isNotBlank).distinct().collect(Collectors.joining(",")));
        this.saveOrUpdate(rule);
        ruleMatService.remove(new LambdaUpdateWrapper<MesInspectionRuleMatEntity>()
                .eq(MesInspectionRuleMatEntity::getRuleCode, rule.getCode()));
        distinctMats.values().forEach(item -> item.setId(null).setRuleCode(rule.getCode()));
        ruleMatService.saveBatch(distinctMats.values());
    }

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    @Override
    public MesInspectionRuleEntity detailById(Long id) {
        MesInspectionRuleEntity result = this.getById(id);
        if (result == null) {
            throw new CheckedException("质检规则不存在或已删除");
        }
        result.setMatList(ruleMatService.list(new LambdaUpdateWrapper<MesInspectionRuleMatEntity>()
                .eq(MesInspectionRuleMatEntity::getRuleCode, result.getCode())));
        return result;
    }

    /**
     * 获取质检任务生成规则
     *
     * @param matCode
     * @return
     */
    @Override
    public MesInspectionRuleEntity getRuleByMatCode(String matCode) {
        MesInspectionRuleEntity result = null;
        MesInspectionRuleMatEntity ruleMat = ruleMatService.getOne(new LambdaUpdateWrapper<MesInspectionRuleMatEntity>()
                .eq(MesInspectionRuleMatEntity::getMatCode, matCode));
        if (Objects.nonNull(ruleMat)) {
            result = this.getOne(new LambdaUpdateWrapper<MesInspectionRuleEntity>()
                    .eq(MesInspectionRuleEntity::getCode, ruleMat.getRuleCode())
                    .eq(MesInspectionRuleEntity::getStatus, "1"));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeData(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            throw new CheckedException("请选择要删除的质检规则");
        }
        List<MesInspectionRuleEntity> rules = this.listByIds(ids);
        if (rules.size() != new HashSet<>(ids).size()) {
            throw new CheckedException("部分质检规则不存在或已删除，请刷新后重试");
        }
        if (rules.stream().anyMatch(rule -> "1".equals(rule.getStatus()))) {
            throw new CheckedException("启用中的质检规则不可删除，请先停用");
        }
        List<String> ruleCodes = rules.stream().map(MesInspectionRuleEntity::getCode).toList();
        if (genRecordService.count(new LambdaQueryWrapper<MesInspectionGenRecordEntity>()
                .in(MesInspectionGenRecordEntity::getRuleCode, ruleCodes)) > 0) {
            throw new CheckedException("已有质检任务生成历史的规则不可删除，可保留为停用状态");
        }
        ruleMatService.remove(new LambdaQueryWrapper<MesInspectionRuleMatEntity>()
                .in(MesInspectionRuleMatEntity::getRuleCode, ruleCodes));
        this.removeBatchByIds(ids);
    }

    private String normalizeInspectionTypes(String inspectionTypes) {
        if (StrUtil.isBlank(inspectionTypes)) {
            throw new CheckedException("至少选择一种质检类型");
        }
        Map<String, String> aliases = new HashMap<>();
        for (ProcedureLabelTypeEnum type : ProcedureLabelTypeEnum.values()) {
            aliases.put(type.name(), type.name());
            aliases.put(type.getName(), type.name());
        }
        LinkedHashSet<String> normalized = Arrays.stream(inspectionTypes.split(","))
                .map(String::trim).filter(StrUtil::isNotBlank).map(value -> {
                    String code = aliases.get(value.toUpperCase());
                    if (code == null) {
                        code = aliases.get(value);
                    }
                    if (code == null) {
                        throw new CheckedException("不支持的质检类型：" + value);
                    }
                    return code;
                }).collect(Collectors.toCollection(LinkedHashSet::new));
        if (!normalized.contains(ProcedureLabelTypeEnum.FIRST.name())) {
            normalized.add(ProcedureLabelTypeEnum.FIRST.name());
        }
        return String.join(",", normalized);
    }
}
