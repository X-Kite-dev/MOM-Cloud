package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.vo.MdmWarehouseModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseExcelVO;
import com.kitex.mom.mdm.vo.excel.MdmWarehouseModelExcelVO;
import org.springframework.validation.BindingResult;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface MdmWarehouseService extends IService<MdmWarehouseEntity> {

    /**
     * 当前实际租户/工厂全部未删除仓库，包含停用状态。
     */
    List<MdmWarehouseEntity> menuConfigurationWarehouses();

    Map<String, Boolean> menuStates();

    /**
     * 整批通过业务历史检查后清理配置，再删除已停用主数据；远程清理可幂等重试。
     */
    boolean removeUnusedWarehouses(Collection<Long> ids);

    /**
     * 仅更新当前范围内实际仓库的批次/容器配置。
     */
    boolean updateWarehouseConfig(MdmWarehouseEntity warehouse);

    /**
     * 获取仓库模树形结构
     */
    List<MdmWarehouseModelTreeVO> getWarehouseModelTree();

    /**
     * 导入excel 表格
     *
     * @param excelVOList   仓库列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importWarehouse(List<MdmWarehouseModelExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出
     *
     * @param mdmWarehouse
     * @return
     */
    List<MdmWarehouseExcelVO> export(MdmWarehouseEntity mdmWarehouse);

    /**
     * 导出excel 表格(仓储、库区)
     *
     * @return excel 文件流
     */
    List<MdmWarehouseModelExcelVO> exportAll();
}
