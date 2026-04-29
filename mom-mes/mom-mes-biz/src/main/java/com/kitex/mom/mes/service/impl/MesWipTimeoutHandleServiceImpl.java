package com.kitex.mom.mes.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesWipTimeoutHandleEntity;
import com.kitex.mom.mes.mapper.MesWipTimeoutHandleMapper;
import com.kitex.mom.mes.service.MesWipTimeoutHandleService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@AllArgsConstructor
public class MesWipTimeoutHandleServiceImpl extends ServiceImpl<MesWipTimeoutHandleMapper, MesWipTimeoutHandleEntity>
        implements MesWipTimeoutHandleService {

    @Override
    public IPage<MesWipTimeoutHandleEntity> queryPage(Page<MesWipTimeoutHandleEntity> page, MesWipTimeoutHandleEntity query) {
        return baseMapper.queryPage(page, query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(MesWipTimeoutHandleEntity handle) {
        MomUser user = SecurityUtils.getUser();
        handle.setHandlePersonCode(user.getUsername());
        handle.setHandlePersonName(user.getUsername());
        handle.setHandleTime(LocalDateTime.now());
        handle.setStatus("1");
        this.updateById(handle);
    }
}
