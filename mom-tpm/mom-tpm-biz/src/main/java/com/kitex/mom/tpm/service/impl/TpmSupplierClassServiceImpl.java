package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmSupplierClassEntity;
import com.kitex.mom.tpm.entity.TpmSupplierEntity;
import com.kitex.mom.tpm.mapper.TpmSupplierClassMapper;
import com.kitex.mom.tpm.mapper.TpmSupplierMapper;
import com.kitex.mom.tpm.service.TpmSupplierClassService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备供应商类型表
 *
 * @author kitex
 * @date 2025-03-25 13:42:38
 */
@Service
@AllArgsConstructor
public class TpmSupplierClassServiceImpl extends ServiceImpl<TpmSupplierClassMapper, TpmSupplierClassEntity> implements TpmSupplierClassService {

    private final TpmSupplierMapper tpmSupplierMapper;

    @Override
    public Boolean removeClass(ArrayList<Long> idList) {
        //查询主表
        List<TpmSupplierClassEntity> tpmSupplierClassEntities = this.listByIds(idList);
        List<String> codeList = tpmSupplierClassEntities.stream().map(TpmSupplierClassEntity::getCode).distinct().toList();

        List<TpmSupplierEntity> tpmSupplierEntities = tpmSupplierMapper.selectList(new LambdaQueryWrapper<TpmSupplierEntity>()
                .in(TpmSupplierEntity::getClassCode, codeList));
        if (CollUtil.isNotEmpty(tpmSupplierEntities)) {
            throw new RuntimeException("供应商类型下存在供应商，请先解除使用！");
        }
        return this.removeBatchByIds(tpmSupplierClassEntities);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateClassById(TpmSupplierClassEntity tpmSupplierClass) {
        //更新主表
        //更新供应商表
        tpmSupplierMapper.update(new UpdateWrapper<TpmSupplierEntity>()
                .lambda().eq(TpmSupplierEntity::getClassCode, tpmSupplierClass.getCode())
                .set(TpmSupplierEntity::getClassName, tpmSupplierClass.getName()));
        return this.updateById(tpmSupplierClass);
    }
}