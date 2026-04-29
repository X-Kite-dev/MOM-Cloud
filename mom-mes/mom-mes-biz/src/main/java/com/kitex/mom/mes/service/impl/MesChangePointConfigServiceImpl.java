package com.kitex.mom.mes.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesChangePointConfigEntity;
import com.kitex.mom.mes.mapper.MesChangePointConfigMapper;
import com.kitex.mom.mes.service.MesChangePointConfigService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 变化点配置
 *
 * @author kitex
 * @since 2025-05-26
 */
@Service
@AllArgsConstructor
public class MesChangePointConfigServiceImpl extends ServiceImpl<MesChangePointConfigMapper, MesChangePointConfigEntity> implements MesChangePointConfigService {

    /**
     * 保存配置
     *
     * @param config
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void set(MesChangePointConfigEntity config) {
        MesChangePointConfigEntity updateData = this.getById(config.getId());
        MomUser user = SecurityUtils.getUser();
        updateData.setSetPersonCode(user.getUsername());
        updateData.setSetPersonName(user.getUsername());
        this.updateById(config);
    }
}
