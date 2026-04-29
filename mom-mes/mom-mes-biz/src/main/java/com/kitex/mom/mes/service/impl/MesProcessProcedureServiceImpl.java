package com.kitex.mom.mes.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.feign.RemoteDeviceService;
import com.kitex.mom.mdm.feign.RemoteProcessRouteService;
import com.kitex.mom.mes.dto.MesProcessProcedureMoldDTO;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;
import com.kitex.mom.mes.mapper.MesProcessProcedureMoldMapper;
import com.kitex.mom.mes.service.MesProcessProcedureService;
import com.kitex.mom.tpm.api.feign.RemoteTpmDeviceService;
import com.kitex.mom.tpm.entity.TpmDeviceMoldRelaEntity;
import lombok.AllArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@AllArgsConstructor
public class MesProcessProcedureServiceImpl extends ServiceImpl<MesProcessProcedureMoldMapper, MesProcessProcedureMoldEntity> implements MesProcessProcedureService {

    private final RemoteProcessRouteService processRouteService;
    private final RemoteTpmDeviceService tpmDeviceService;
    private final RemoteDeviceService deviceService;

    /**
     * 查询工艺路线下工序绑定的模具信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    @Override
    public List<MesProcessProcedureMoldEntity> getMold(Long id) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = processRouteService.getProcessWorkingById(id).getData();
        return baseMapper.selectList(new LambdaQueryWrapper<MesProcessProcedureMoldEntity>()
                .eq(MesProcessProcedureMoldEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MesProcessProcedureMoldEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode()));
    }

    /**
     * 新增工艺路线下工序绑定的模具信息
     *
     * @param mesProcessProcedureMoldDTO 工艺路线-工序-模具
     * @return R
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveMold(MesProcessProcedureMoldDTO mesProcessProcedureMoldDTO) {
        // 查询工艺路线-工序数据
        MdmProcessRouteWorkingEntity mdmProcessRouteWorkingEntity = processRouteService.getProcessWorkingById(mesProcessProcedureMoldDTO.getId()).getData();
        // 删除工艺路线-工序-工作中心数据
        baseMapper.delete(new LambdaQueryWrapper<MesProcessProcedureMoldEntity>()
                .eq(MesProcessProcedureMoldEntity::getRouteCode, mdmProcessRouteWorkingEntity.getRouteCode())
                .eq(MesProcessProcedureMoldEntity::getProcessCode, mdmProcessRouteWorkingEntity.getProcessCode()));
        // 插入工艺路线-工序-工作中心数据
        if (!mesProcessProcedureMoldDTO.getMoldList().isEmpty()) {
            mesProcessProcedureMoldDTO.getMoldList().stream().map(item -> {
                MesProcessProcedureMoldEntity mesProcessProcedureMoldEntity = new MesProcessProcedureMoldEntity();
                BeanUtils.copyProperties(item, mesProcessProcedureMoldEntity);
                mesProcessProcedureMoldEntity.setId(null);
                mesProcessProcedureMoldEntity.setRouteCode(mdmProcessRouteWorkingEntity.getRouteCode());
                mesProcessProcedureMoldEntity.setProcessCode(mdmProcessRouteWorkingEntity.getProcessCode());
                return mesProcessProcedureMoldEntity;
            }).forEach(baseMapper::insert);
        }
        return Boolean.TRUE;
    }


    /**
     * 查询工艺路线下所有可选择的模具列表
     *
     * @param id
     * @return
     */
    @Override
    public List<MdmDeviceEntity> queryMoldList(Long id) {
        // 设备列表
        List<String> deviceBindList = processRouteService.getDeviceList(id).getData();
        if (CollUtil.isEmpty(deviceBindList)) {
            return new ArrayList<>();
        }
        // 设备关联关系
        List<TpmDeviceMoldRelaEntity> relations = tpmDeviceService.getListByDeviceCodes(deviceBindList).getData();
        if (CollUtil.isNotEmpty(relations)) {
            List<String> moldCodes = relations.stream().map(TpmDeviceMoldRelaEntity::getMoldCode).distinct().toList();
            return deviceService.getMdmDeviceListByCodes(moldCodes).getData();
        }
        return new ArrayList<>();
    }

}
