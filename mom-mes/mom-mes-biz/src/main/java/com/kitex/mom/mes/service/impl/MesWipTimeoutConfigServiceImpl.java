package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesWipTimeoutConfigEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutConfigMapper;
import com.kitex.mom.mes.service.MesWipTimeoutConfigService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@AllArgsConstructor
public class MesWipTimeoutConfigServiceImpl extends ServiceImpl<MesWipTimeoutConfigMapper, MesWipTimeoutConfigEntity>
        implements MesWipTimeoutConfigService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void set(MesWipTimeoutConfigEntity config) {
        MomUser user = SecurityUtils.getUser();
        config.setSetPersonCode(user.getUsername());
        config.setSetPersonName(user.getUsername());
        this.updateById(config);
    }
}
