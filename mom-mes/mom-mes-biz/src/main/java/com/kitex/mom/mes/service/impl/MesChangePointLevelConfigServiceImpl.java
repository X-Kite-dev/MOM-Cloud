package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.entity.MesChangePointLevelConfigEntity;
import com.kitex.mom.mes.mapper.MesChangePointLevelConfigMapper;
import com.kitex.mom.mes.service.MesChangePointConfigService;
import com.kitex.mom.mes.service.MesChangePointLevelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MesChangePointLevelConfigServiceImpl extends ServiceImpl<MesChangePointLevelConfigMapper, MesChangePointLevelConfigEntity>
        implements MesChangePointLevelConfigService {

    private final MesChangePointConfigService changePointConfigService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setByConfigCode(MesChangePointLevelConfigEntity config) {
        if (StrUtil.isBlank(config.getConfigCode())) {
            throw new CheckedException("变化点编码不能为空");
        }
        MesChangePointConfigEntity source = changePointConfigService.getOne(
                new LambdaQueryWrapper<MesChangePointConfigEntity>()
                        .eq(MesChangePointConfigEntity::getConfigCode, config.getConfigCode()).last("LIMIT 1"), false);
        if (source == null) {
            throw new CheckedException("变化点配置不存在");
        }
        if (!Boolean.TRUE.equals(source.getEnabled())) {
            throw new CheckedException("仅启用的变化点配置可以设置等级规则");
        }
        config.setCode(source.getCode());
        config.setType(source.getType());
        config.setContent(source.getContent());
        config.setFactoryCode(source.getFactoryCode());
        config.setEnabled(!Boolean.FALSE.equals(config.getEnabled()));
        if (StrUtil.isBlank(config.getLevel())) {
            throw new CheckedException("变化点等级不能为空");
        }
        if (Boolean.TRUE.equals(source.getIsVerify()) && StrUtil.isBlank(config.getVerifyRole())) {
            throw new CheckedException("主配置要求验证，等级规则必须指定验证角色");
        }
        if (config.getUpperLimit() != null && config.getLowerLimit() != null
                && config.getUpperLimit().compareTo(config.getLowerLimit()) <= 0) {
            throw new CheckedException("上限必须大于下限");
        }
        if ((config.getUpperLimit() == null) != (config.getLowerLimit() == null)) {
            throw new CheckedException("等级阈值必须同时设置上限和下限");
        }

        MesChangePointLevelConfigEntity exists = this.getOne(new LambdaQueryWrapper<MesChangePointLevelConfigEntity>()
                .eq(MesChangePointLevelConfigEntity::getConfigCode, config.getConfigCode()), false);
        if (exists == null) {
            this.save(config);
            return;
        }
        config.setId(exists.getId());
        this.updateById(config);
    }
}
