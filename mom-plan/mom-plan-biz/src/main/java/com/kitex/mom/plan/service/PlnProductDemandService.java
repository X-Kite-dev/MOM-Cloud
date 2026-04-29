package com.kitex.mom.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.plan.dto.TemporaryPlanQueryDTO;
import com.kitex.mom.plan.entity.PlnProductDemandEntity;
import com.kitex.mom.plan.vo.PlnProductDemandExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

/**
 * 计划接收表服务类
 */
public interface PlnProductDemandService extends IService<PlnProductDemandEntity> {

    /**
     * 导出
     *
     * @param planMesPlan
     * @param ids
     * @return
     */
    List<PlnProductDemandExcelVO> exportExcel(PlnProductDemandEntity planMesPlan, Long[] ids);

    /**
     * 计划接收
     *
     * @param planIds
     * @return
     */
    Boolean receive(Long[] planIds);

    /**
     * 新增生产计划
     *
     * @param planMesPlan
     * @return
     */
    Boolean savePlan(PlnProductDemandEntity planMesPlan);

    /**
     * 通过条件查询需求日期
     *
     * @param templateQueryDTO 查询条件
     */
    List<Map<String, Object>> getDemandDate(TemporaryPlanQueryDTO templateQueryDTO);

    /**
     * 导入客户订单excel 表
     *
     * @param excelVOList   对象实体列表
     * @param bindingResult 错误信息列表
     * @return ok fail
     */
    R importExcel(List<PlnProductDemandExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * mrp运算
     *
     * @param ids 计划ids
     * @return
     */
    R mrp(Long[] ids);
}
