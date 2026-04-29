package com.kitex.mom.pms.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.pms.entity.PmsOrderDetailEntity;
import com.kitex.mom.pms.entity.PmsPlanEntity;
import com.kitex.mom.pms.vo.PmsPlanExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

public interface PmsPlanService extends IService<PmsPlanEntity> {

    /**
     * 导入excel 表格
     *
     * @param excelVOList   采购计划列表
     * @param bindingResult 错误信息列表
     * @param factoryCode   所属工厂编码
     * @return R
     */
    R importPmsPlan(List<PmsPlanExcelVO> excelVOList, BindingResult bindingResult, String factoryCode);

    /**
     * 导出excel 表格
     *
     * @param pmsPlan 查询条件
     * @param ids     导出指定ID
     * @return excel 文件流
     */
    List<PmsPlanExcelVO> export(PmsPlanEntity pmsPlan, Long[] ids);

    /**
     * 新增
     *
     * @param pmsPlan 采购计划
     */
    Boolean savePlan(PmsPlanEntity pmsPlan);


    /**
     * 通过时间和物料编码查询采购计划表
     *
     * @param templateQueryDTO 查询条件
     * @return R
     */
    List<Map<String, String>> getPlnDemand(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 修改采购计划下单数量
     */
    void updatePlanOrderNum(List<PmsPlanEntity> pmsPlanEntityList);

    /**
     * 处理采购计划到货数量
     */
    void updateInNoticeSave(PmsOrderDetailEntity pmsOrderDetail, Double inNum);

    /**
     * 根据采购计划下达号查询采购计划
     *
     * @param orderNo 采购计划下达号
     * @return R
     */
    List<PmsPlanEntity> omsLife(String orderNo);
}