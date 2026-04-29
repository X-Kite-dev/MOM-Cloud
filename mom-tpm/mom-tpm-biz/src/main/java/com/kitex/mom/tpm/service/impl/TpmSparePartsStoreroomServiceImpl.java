package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.tpm.entity.TpmSparePartsLocationEntity;
import com.kitex.mom.tpm.entity.TpmSparePartsStoreroomEntity;
import com.kitex.mom.tpm.mapper.TpmSparePartsLocationMapper;
import com.kitex.mom.tpm.mapper.TpmSparePartsStoreroomMapper;
import com.kitex.mom.tpm.service.TpmSparePartsStoreroomService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 备件库房表
 *
 * @author kitex
 * @date 2025-02-11 16:52:52
 */
@Service
@AllArgsConstructor
public class TpmSparePartsStoreroomServiceImpl extends ServiceImpl<TpmSparePartsStoreroomMapper, TpmSparePartsStoreroomEntity> implements TpmSparePartsStoreroomService {

    private final TpmSparePartsLocationMapper tpmSparePartsLocationMapper;

    @Override
    public Boolean removeStoreroomByIds(ArrayList<Long> toList) {
        if (CollUtil.isEmpty(toList)) {
            throw new RuntimeException("删除项为空！");
        }
        List<TpmSparePartsStoreroomEntity> storeroomList = this.listByIds(toList);
        List<String> codeList = storeroomList.stream().map(TpmSparePartsStoreroomEntity::getCode).toList();
        Long aLong = tpmSparePartsLocationMapper.selectCount(new LambdaQueryWrapper<TpmSparePartsLocationEntity>()
                .in(TpmSparePartsLocationEntity::getStoreroomCode, codeList));
        if (aLong.compareTo(0L) > 0) {
            throw new RuntimeException("库房已被库位绑定，请先删除绑定的库位数据！");
        }
        return this.removeBatchByIds(toList);
    }
}