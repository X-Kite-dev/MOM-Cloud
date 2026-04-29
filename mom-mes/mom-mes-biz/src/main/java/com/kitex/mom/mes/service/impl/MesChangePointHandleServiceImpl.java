package com.kitex.mom.mes.service.impl;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.security.service.MomUser;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.mes.entity.MesChangePointHandleEntity;
import com.kitex.mom.mes.mapper.MesChangePointHandleMapper;
import com.kitex.mom.mes.service.MesChangePointHandleService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 变化点配置
 *
 * @author kitex
 * @since 2025-05-26
 */
@Service
@AllArgsConstructor
public class MesChangePointHandleServiceImpl extends ServiceImpl<MesChangePointHandleMapper, MesChangePointHandleEntity> implements MesChangePointHandleService {

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesChangePointHandleEntity> queryPage(Page<MesChangePointHandleEntity> pageParam, MesChangePointHandleEntity query) {
        MomUser user = SecurityUtils.getUser();
        query.setNoticePersonCode(user.getUsername());
        return baseMapper.queryPage(pageParam, query);
    }

    /**
     * 通知
     *
     * @param handle
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void notice(MesChangePointHandleEntity handle) {
        handle.setTriggerTime(LocalDateTime.now());
        handle.setStatus("0");
        this.save(handle);
    }

    /**
     * 处理
     *
     * @param mesChangePointHandle
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(MesChangePointHandleEntity mesChangePointHandle) {
        MomUser user = SecurityUtils.getUser();
        mesChangePointHandle.setHandlePersonCode(user.getUsername());
        mesChangePointHandle.setHandlePersonName(user.getName());
        mesChangePointHandle.setStatus("1");
        this.updateById(mesChangePointHandle);
    }
}
