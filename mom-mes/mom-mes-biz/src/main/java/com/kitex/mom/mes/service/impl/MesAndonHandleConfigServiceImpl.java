package com.kitex.mom.mes.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mes.dto.MesAndonHandleConfigVO;
import com.kitex.mom.mes.entity.MesAndonHandleConfigEntity;
import com.kitex.mom.mes.entity.MesAndonHandleConfigStaffEntity;
import com.kitex.mom.mes.enums.AndonHandleConfigLevelEnum;
import com.kitex.mom.mes.mapper.MesAndonHandleConfigMapper;
import com.kitex.mom.mes.service.MesAndonHandleConfigService;
import com.kitex.mom.mes.service.MesAndonHandleConfigStaffService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 安灯异常处理配置
 *
 * @author kitex
 * @date 2025-02-18 16:20:02
 */
@Service
@AllArgsConstructor
public class MesAndonHandleConfigServiceImpl extends ServiceImpl<MesAndonHandleConfigMapper, MesAndonHandleConfigEntity> implements MesAndonHandleConfigService {

    private final RemoteSeqConfigService seqConfigService;
    private final MesAndonHandleConfigStaffService staffService;

    /**
     * 保存
     *
     * @param config
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveData(MesAndonHandleConfigVO config) {
        long count = this.count(new LambdaQueryWrapper<MesAndonHandleConfigEntity>()
                        .eq(MesAndonHandleConfigEntity::getTypeCode, config.getTypeCode())
                        .eq(MesAndonHandleConfigEntity::getShiftCode, config.getShiftCode())
//                .and(i -> i.like(MesAndonHandleConfigEntity::getShiftCode, config.getShiftCode()).or().like(MesAndonHandleConfigEntity::getShiftCode, AndonShiftEnum.ALL.getCode()))
        );
        if (count > 0) {
            throw new RuntimeException("安灯异常处理配置设置重复，不可提交");
        }

        MesAndonHandleConfigEntity saveConfig = new MesAndonHandleConfigEntity();
        BeanUtil.copyProperties(config, saveConfig);
        saveConfig.setCode(seqConfigService.create("mes_andon_config_code").getData());

        // 保存关联人员信息
        List<MesAndonHandleConfigStaffEntity> saveStaffList = new ArrayList<>();
        if (CollUtil.isNotEmpty(config.getGeneralList())) {
            saveStaffList.addAll(config.getGeneralList());
        }
        if (CollUtil.isNotEmpty(config.getSeriousList())) {
            saveStaffList.addAll(config.getSeriousList());
        }

        if (CollUtil.isNotEmpty(saveStaffList)) {
            saveStaffList.forEach(item -> item.setConfigCode(saveConfig.getCode()));
            staffService.saveBatch(saveStaffList);
        }

        this.save(saveConfig);
    }

    /**
     * 更新
     *
     * @param config
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConfig(MesAndonHandleConfigVO config) {
        long count = this.count(new LambdaQueryWrapper<MesAndonHandleConfigEntity>()
                        .eq(MesAndonHandleConfigEntity::getTypeCode, config.getTypeCode())
                        .eq(MesAndonHandleConfigEntity::getShiftCode, config.getShiftCode())
                        .ne(MesAndonHandleConfigEntity::getId, config.getId())
//                .and(i -> i.like(MesAndonHandleConfigEntity::getShiftCode, config.getShiftCode()).or().like(MesAndonHandleConfigEntity::getShiftCode, AndonShiftEnum.ALL.getCode()))
        );
        if (count > 0) {
            throw new RuntimeException("安灯异常处理配置设置重复，不可提交");
        }

        MesAndonHandleConfigEntity saveConfig = new MesAndonHandleConfigEntity();
        BeanUtil.copyProperties(config, saveConfig);

        staffService.remove(new LambdaUpdateWrapper<MesAndonHandleConfigStaffEntity>()
                .eq(MesAndonHandleConfigStaffEntity::getConfigCode, config.getCode()));

        // 保存关联人员信息
        List<MesAndonHandleConfigStaffEntity> saveStaffList = new ArrayList<>();
        if (CollUtil.isNotEmpty(config.getGeneralList())) {
            saveStaffList.addAll(config.getGeneralList());
        }
        if (CollUtil.isNotEmpty(config.getSeriousList())) {
            saveStaffList.addAll(config.getSeriousList());
        }

        if (CollUtil.isNotEmpty(saveStaffList)) {
            saveStaffList.forEach(item -> {
                item.setConfigCode(saveConfig.getCode());
                item.setId(null);
            });
            staffService.saveBatch(saveStaffList);
        }

        this.updateById(saveConfig);
    }

    /**
     * 查询详情
     *
     * @param id
     * @return
     */
    @Override
    public MesAndonHandleConfigVO getConfig(Long id) {
        MesAndonHandleConfigVO result = new MesAndonHandleConfigVO();
        MesAndonHandleConfigEntity config = this.getById(id);
        BeanUtil.copyProperties(config, result);
        // 关联表
        List<MesAndonHandleConfigStaffEntity> allList = staffService.list(new LambdaUpdateWrapper<MesAndonHandleConfigStaffEntity>()
                .eq(MesAndonHandleConfigStaffEntity::getConfigCode, config.getCode()));
        result.setGeneralList(allList.stream().filter(item -> AndonHandleConfigLevelEnum.GENERAL.getCode().equals(item.getLevel())).collect(Collectors.toList()));
        result.setSeriousList(allList.stream().filter(item -> AndonHandleConfigLevelEnum.SERIOUS.getCode().equals(item.getLevel())).collect(Collectors.toList()));
        return result;
    }

}