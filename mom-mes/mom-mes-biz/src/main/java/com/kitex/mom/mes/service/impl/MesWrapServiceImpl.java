package com.kitex.mom.mes.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.barcode.api.feign.RemoteBarcodeService;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.entity.MesWrapEntity;
import com.kitex.mom.mes.entity.MesWrapRuleEntity;
import com.kitex.mom.mes.mapper.MesWrapMapper;
import com.kitex.mom.mes.service.MesWrapRuleService;
import com.kitex.mom.mes.service.MesWrapService;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 箱码管理
 *
 * @author kitex
 * @since 2025-03-25
 */
@Service
@AllArgsConstructor
public class MesWrapServiceImpl extends ServiceImpl<MesWrapMapper, MesWrapEntity> implements MesWrapService {

    private final RemoteBarcodeService remoteBarcodeService;
    private final MesWrapRuleService ruleService;
    private final RemoteSeqConfigService seqConfigService;

    /**
     * 生成箱码
     *
     * @param mesWrap
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @SneakyThrows
    public String addData(MesWrapEntity mesWrap) {
        String mesWrapCode;
        MesWrapRuleEntity rule = ruleService.getOne(new LambdaQueryWrapper<MesWrapRuleEntity>()
                .eq(MesWrapRuleEntity::getWrapCode, mesWrap.getRuleCode())
                .eq(MesWrapRuleEntity::getStatus, Boolean.TRUE)
                .orderByDesc(MesWrapRuleEntity::getCreateTime)
                .last(" limit 1"));
        if (StrUtil.isNotEmpty(mesWrap.getTaskCode())) {
            JSONObject object = new JSONObject();
            object.append("boxCode", mesWrap.getTaskCode().substring(2));
            object.append("capacity", rule.getCapacity());
            mesWrapCode = remoteBarcodeService.genBarcode(object, rule.getWrapCode()).getData();
        } else {
            mesWrapCode = seqConfigService.create("mes_wrap").getData();
        }
        mesWrap.setWrapCode(mesWrapCode);
        mesWrap.setWrapCapacity(rule.getCapacity());
        mesWrap.setWrapType(mesWrap.getWrapType());
        this.save(mesWrap);
        return mesWrapCode;
    }

    /**
     * 分页查询
     *
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<MesWrapEntity> queryPage(Page<MesWrapEntity> pageParam, MesWrapEntity query) {
        return baseMapper.queryPage(pageParam, query);
    }
} 