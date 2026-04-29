package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.entity.MesInspectionRuleEntity;
import com.kitex.mom.mes.entity.MesInspectionRuleMatEntity;
import com.kitex.mom.mes.mapper.MesInspectionRuleMapper;
import com.kitex.mom.mes.service.MesInspectionRuleMatService;
import com.kitex.mom.mes.service.MesInspectionRuleService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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
    private final RemoteSeqConfigService seqConfigService;

    /**
     * 保存
     *
     * @param rule
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveData(MesInspectionRuleEntity rule) {
        if (Objects.isNull(rule.getId())) {
            rule.setCode(seqConfigService.create("mes_inspection_rule_code").getData());
        }
        this.saveOrUpdate(rule);
        if (CollUtil.isEmpty(rule.getMatList())) {
            throw new RuntimeException("关联产品为空，保存失败");
        } else {
            rule.getMatList().forEach(item -> item.setRuleCode(rule.getCode()));
            List<String> matCodes = rule.getMatList().stream().map(MesInspectionRuleMatEntity::getMatCode).toList();
            long existCount = ruleMatService.count(new LambdaUpdateWrapper<MesInspectionRuleMatEntity>()
                    .in(MesInspectionRuleMatEntity::getMatCode, matCodes));
            if (existCount > 0) {
                throw new RuntimeException("关联产品中存在已设置规则的产品，请勿重复保存");
            }
            ruleMatService.remove(new LambdaUpdateWrapper<MesInspectionRuleMatEntity>()
                    .eq(MesInspectionRuleMatEntity::getRuleCode, rule.getCode()));
            ruleMatService.saveBatch(rule.getMatList());
        }
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
                    .eq(MesInspectionRuleEntity::getCode, ruleMat.getRuleCode()));
        }
        return result;
    }
} 