package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmRepairFaultClassEntity;
import com.kitex.mom.tpm.entity.TpmRepairRemindDetailsEntity;
import com.kitex.mom.tpm.entity.TpmRepairRemindEntity;
import com.kitex.mom.tpm.mapper.TpmRepairRemindMapper;
import com.kitex.mom.tpm.service.TpmRepairFaultClassService;
import com.kitex.mom.tpm.service.TpmRepairRemindDetailsService;
import com.kitex.mom.tpm.service.TpmRepairRemindService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 提醒配置表
 *
 * @author kitex
 * @date 2025-02-06 15:39:14
 */
@Service
@AllArgsConstructor
public class TpmRepairRemindServiceImpl extends ServiceImpl<TpmRepairRemindMapper, TpmRepairRemindEntity> implements TpmRepairRemindService {

    private final TpmRepairFaultClassService tpmRepairFaultClassService;
    private final TpmRepairRemindDetailsService tpmRepairRemindDetailsService;

    @Override
    public Page<TpmRepairRemindEntity> pageList(Page toMpPage, TpmRepairRemindEntity tpmRepairRemind) {
        return this.baseMapper.pageList(toMpPage, tpmRepairRemind);
    }

    @Override
    public TpmRepairRemindEntity getRemindById(Long id) {
        //查询主表
        TpmRepairRemindEntity byId = this.getById(id);
        //查询故障名称
        TpmRepairFaultClassEntity faultClass = tpmRepairFaultClassService.getOne(new LambdaQueryWrapper<TpmRepairFaultClassEntity>()
                .eq(TpmRepairFaultClassEntity::getId, byId.getFaultClassId()));
        byId.setFaultClassName(faultClass.getName());
        //查询配置人员详情
        List<TpmRepairRemindDetailsEntity> detailsList = tpmRepairRemindDetailsService.list(new LambdaQueryWrapper<TpmRepairRemindDetailsEntity>()
                .eq(TpmRepairRemindDetailsEntity::getRemindCode, byId.getRemindCode())
                .orderByAsc(TpmRepairRemindDetailsEntity::getPersonType)
                .orderByAsc(TpmRepairRemindDetailsEntity::getUserLevel));
        byId.setDetailsList(detailsList);
        return byId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveRemind(TpmRepairRemindEntity tpmRepairRemind) {
        List<TpmRepairRemindDetailsEntity> detailsList = tpmRepairRemind.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("人员配置不能为空！");
        }
        //保存主表
        this.save(tpmRepairRemind);
        //保存子表
        detailsList.forEach(item -> {
            item.setId(null);
            item.setRemindCode(tpmRepairRemind.getRemindCode());
        });
        tpmRepairRemindDetailsService.saveBatch(tpmRepairRemind.getDetailsList());
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateRemindById(TpmRepairRemindEntity tpmRepairRemind) {
        List<TpmRepairRemindDetailsEntity> detailsList = tpmRepairRemind.getDetailsList();
        if (CollUtil.isEmpty(detailsList)) {
            throw new RuntimeException("人员配置不能为空！");
        }
        //更新主表
        this.updateById(tpmRepairRemind);
        //更新子表
        tpmRepairRemindDetailsService.remove(new LambdaQueryWrapper<TpmRepairRemindDetailsEntity>()
                .eq(TpmRepairRemindDetailsEntity::getRemindCode, tpmRepairRemind.getRemindCode()));
        detailsList.forEach(item -> {
            item.setId(null);
            item.setRemindCode(tpmRepairRemind.getRemindCode());
        });
        tpmRepairRemindDetailsService.saveBatch(detailsList);
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeRemindById(ArrayList<Long> toList) {
        List<TpmRepairRemindEntity> tpmRepairRemindEntities = this.listByIds(toList);
        //删除子表
        List<String> codeList = tpmRepairRemindEntities.stream().map(TpmRepairRemindEntity::getRemindCode).toList();
        if (CollUtil.isNotEmpty(codeList)) {
            tpmRepairRemindDetailsService.remove(new LambdaQueryWrapper<TpmRepairRemindDetailsEntity>()
                    .in(TpmRepairRemindDetailsEntity::getRemindCode, codeList));
        }
        //删除主表
        this.removeByIds(toList);
        return Boolean.TRUE;
    }
}