package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmDeviceEntity;
import com.kitex.mom.mes.dto.MesProcessProcedureMoldDTO;
import com.kitex.mom.mes.dto.MesRouteMoldConfigurationDTO;
import com.kitex.mom.mes.entity.MesProcessProcedureMoldEntity;

import java.util.List;

/**
 * 模具绑定
 *
 * @author kitex
 * @since 2025-04-11
 */
public interface MesProcessProcedureService extends IService<MesProcessProcedureMoldEntity> {

    /** 精确节点的维护快照，包含原父图版本及真实绑定。 */
    MesRouteMoldConfigurationDTO configuration(Long id);

    /** 按稳定节点读取当前工程模具，不能回退主工序匹配。 */
    List<MesProcessProcedureMoldEntity> getMoldByNode(String routeCode, String routeNodeCode);


    /**
     * 查询工艺路线下工序绑定的模具信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    List<MesProcessProcedureMoldEntity> getMold(Long id);

    /** 旧编码入口也须先证明当前工厂路线中的主工序唯一，不能合并重复节点的模具。 */
    List<MesProcessProcedureMoldEntity> getMoldByCode(String routeCode, String processCode);

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
