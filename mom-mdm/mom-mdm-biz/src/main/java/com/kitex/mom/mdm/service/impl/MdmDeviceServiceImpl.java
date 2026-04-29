package com.kitex.mom.mdm.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.common.core.util.R;
import com.kitex.common.excel.vo.ErrorMessage;
import com.kitex.mom.mdm.entity.MdmDeviceClassEntity;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.enums.DeviceStateEnum;
import com.kitex.mom.mdm.enums.DeviceUsableEnum;
import com.kitex.mom.mdm.mapper.MdmDeviceMapper;
import com.kitex.mom.mdm.service.MdmDeviceClassService;
import com.kitex.mom.mdm.service.MdmDeviceService;
import com.kitex.mom.mdm.vo.excel.MdmDeviceExcelVO;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindingResult;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 设备
 *
 * @author kitex
 * @date 2024-10-28 17:29:52
 */
@Service
@AllArgsConstructor
public class MdmDeviceServiceImpl extends ServiceImpl<MdmDeviceMapper, MdmDeviceEntity> implements MdmDeviceService {

    private final MdmDeviceClassService mdmDeviceClassService;

    /**
     * 导入设备
     *
     * @param mdmDeviceList
     * @param bindingResult
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public R importExcel(List<MdmDeviceExcelVO> mdmDeviceList, BindingResult bindingResult) {
        List<ErrorMessage> errorMessageList = (List<ErrorMessage>) bindingResult.getTarget();

        // 设备map
        Map<String, MdmDeviceEntity> mdmDeviceMap = this.list()
                .stream()
                .collect(Collectors.toMap(MdmDeviceEntity::getDeviceCode, Function.identity(), (a, b) -> a));
        // 设备分类map
        Map<String, MdmDeviceClassEntity> mdmDeviceClassEntityMap = mdmDeviceClassService.list()
                .stream()
                .collect(Collectors.toMap(MdmDeviceClassEntity::getCode, Function.identity(), (a, b) -> a));
        List<MdmDeviceEntity> saveList = new ArrayList<>();

        for (MdmDeviceExcelVO mdmDevice : mdmDeviceList) {
            Set<String> errorMsg = new HashSet<>();
            if (mdmDeviceMap.containsKey(mdmDevice.getDeviceCode().trim())) {
                errorMsg.add(mdmDevice.getDeviceCode() + "设备编码已存在");
            }
            MdmDeviceClassEntity mdmDeviceClassEntity = mdmDeviceClassEntityMap.get(mdmDevice.getClassCode());
            if (mdmDeviceClassEntity != null) {
                mdmDevice.setClassName(mdmDeviceClassEntity.getName());
            } else {
                errorMsg.add(mdmDevice.getClassCode() + "设备分类编码不存在");
            }
            // 设备状态
            mdmDevice.setStatus("1");

            if (CollUtil.isEmpty(errorMsg)) {
                // 数据合法情况
                MdmDeviceEntity mdmDeviceEntity = insert(mdmDevice);
                saveList.add(mdmDeviceEntity);
                mdmDeviceMap.put(mdmDevice.getDeviceCode(), mdmDeviceEntity);
            } else {
                // 数据不合法情况
                errorMessageList.add(new ErrorMessage(mdmDevice.getLineNum() + 1, errorMsg));
            }
        }
        // 保存信息
        this.saveBatch(saveList);
        if (CollUtil.isNotEmpty(errorMessageList)) {
            return R.failed(errorMessageList);
        }
        return R.ok(null, "导入成功");
    }

    /**
     * 新增设备
     *
     * @param mdmDevice 主体
     * @return Boolean
     */
    @Override
    public Boolean saveDevice(MdmDeviceEntity mdmDevice) {
        Long count = this.count(new LambdaQueryWrapper<MdmDeviceEntity>()
                .eq(MdmDeviceEntity::getDeviceCode, mdmDevice.getDeviceCode()));
        if (count.compareTo(0L) > 0) {
            throw new RuntimeException("存在重复设备编码！");
        }
        //设备初始状态
        mdmDevice.setDeviceUsable(DeviceUsableEnum.YES.getCode());
        mdmDevice.setDeviceState(DeviceStateEnum.IDLE.getCode());
        return this.save(mdmDevice);
    }

    // 插入数据
    private MdmDeviceEntity insert(MdmDeviceExcelVO mdmDevice) {
        MdmDeviceEntity device = new MdmDeviceEntity();
        BeanUtils.copyProperties(mdmDevice, device);
        device.setDeviceState("0");
        return device;
    }

}
