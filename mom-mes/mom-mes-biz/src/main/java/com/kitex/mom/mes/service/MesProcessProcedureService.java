package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mes.dto.MesProcessProcedureMoldDTO;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;

import java.util.List;

/**
 * 模具绑定
 *
 * @author kitex
 * @since 2025-04-11
 */
public interface MesProcessProcedureService extends IService<MesProcessProcedureMoldEntity> {


    /**
     * 查询工艺路线下工序绑定的模具信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    List<MesProcessProcedureMoldEntity> getMold(Long id);

    /**
     * 新增工艺路线下工序绑定的模具信息
     *
     * @param mdmProcessProcedureMoldDTO 工艺路线-工序-模具
     * @return R
     */
    Boolean saveMold(MesProcessProcedureMoldDTO mdmProcessProcedureMoldDTO);

    /**
     * 查询工艺路线下所有可选择的模具列表
     *
     * @param id
     * @return
     */
    List<MdmDeviceEntity> queryMoldList(Long id);
}
