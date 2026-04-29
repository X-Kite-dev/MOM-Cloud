package com.kitex.mom.tpm.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.tpm.entity.TpmMaintenanceItemEntity;
import com.kitex.mom.tpm.entity.TpmMaintenancePlanDetailsEntity;
import com.kitex.mom.tpm.enums.MaintenanceItemJudgeTypeEnum;
import com.kitex.mom.tpm.mapper.TpmMaintenanceItemMapper;
import com.kitex.mom.tpm.mapper.TpmMaintenancePlanDetailsMapper;
import com.kitex.mom.tpm.service.TpmMaintenanceItemService;
import com.kitex.mom.tpm.vo.TpmMaintenanceItemVo;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 保养项目表
 *
 * @author kitex
 * @date 2025-02-06 15:04:12
 */
@Service
@AllArgsConstructor
public class TpmMaintenanceItemServiceImpl extends ServiceImpl<TpmMaintenanceItemMapper, TpmMaintenanceItemEntity> implements TpmMaintenanceItemService {

    //计划详情
    private final TpmMaintenancePlanDetailsMapper tpmMaintenancePlanDetailsMapper;

    @Override
    public Boolean removeItemById(ArrayList<Long> toList) {
        Long aLong = tpmMaintenancePlanDetailsMapper.selectCount(new LambdaQueryWrapper<TpmMaintenancePlanDetailsEntity>()
                .in(TpmMaintenancePlanDetailsEntity::getItemId, toList));
        if (aLong.compareTo(0L) > 0) {
            throw new RuntimeException("保养项已绑定计划,请先解绑计划！");
        }
        return this.removeBatchByIds(toList);
    }

    @Override
    public R importExcel(List<TpmMaintenanceItemVo> tpmMaintenanceItemVoList, BindingResult bindingResult, String factoryCode) {
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();
        //纠正行号错误
        if (CollUtil.isNotEmpty(errorMessageList)) {
            errorMessageList.forEach(item -> {
                item.setLineNum(item.getLineNum() + 2);
            });
        }
        //校验 1.当判断类型为 值 时 ，上下限不能为空  2.判断编码不能重复
        //获取数据库所有的数据进行比对
        List<TpmMaintenanceItemEntity> dbMaintenanceItemList = this.list();
        Map<String, TpmMaintenanceItemEntity> dbItemMap = dbMaintenanceItemList.stream().collect(Collectors.toMap(TpmMaintenanceItemEntity::getCode, Function.identity(), (k1, k2) -> k1));
        //导入数据进行比对
        Map<String, List<TpmMaintenanceItemVo>> importItemMap = tpmMaintenanceItemVoList.stream().collect(Collectors.groupingBy(TpmMaintenanceItemVo::getCode, Collectors.toList()));

        for (TpmMaintenanceItemVo tpmMaintenanceItemVo : tpmMaintenanceItemVoList) {
            Set<String> errorMsg = new HashSet<>();
            if (MaintenanceItemJudgeTypeEnum.VAL.getName().equals(tpmMaintenanceItemVo.getJudgeType())) {
                if (ObjectUtil.isEmpty(tpmMaintenanceItemVo.getStandardMax()) || ObjectUtil.isEmpty(tpmMaintenanceItemVo.getStandardMin())) {
                    errorMsg.add("标准上限或下限不能为空！");
                }
            }
            if (dbItemMap.containsKey(tpmMaintenanceItemVo.getCode())) {
                errorMsg.add("数据库已存在编码为: " + tpmMaintenanceItemVo.getCode() + "的保养项目！");
            }
            List<TpmMaintenanceItemVo> tpmMaintenanceItemVos = importItemMap.get(tpmMaintenanceItemVo.getCode());
            if (tpmMaintenanceItemVos.size() > 1) {
                errorMsg.add("导入数据中存在重复的编码为 : " + tpmMaintenanceItemVo.getCode() + "的保养项目！");
            }

            if (CollUtil.isNotEmpty(errorMsg)) {
                errorMessageList.add(new ErrorMessage(tpmMaintenanceItemVo.getLineNum() + 2, errorMsg));
            }

        }

        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        List<TpmMaintenanceItemEntity> itemEntities = tpmMaintenanceItemVoList.stream().map(item -> {
            TpmMaintenanceItemEntity entity = new TpmMaintenanceItemEntity();
            BeanUtils.copyProperties(item, entity);
            entity.setJudgeType(MaintenanceItemJudgeTypeEnum.getCodeByName(entity.getJudgeType()));
            entity.setFactoryCode(factoryCode);
            return entity;
        }).collect(Collectors.toList());
        return R.ok(this.saveBatch(itemEntities));
    }

}
