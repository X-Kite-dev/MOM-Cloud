package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.dto.MdmMaterialMeasureDTO;
import com.kitex.mom.mdm.dto.MdmMaterialWarningConfigDTO;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.vo.excel.MdmMaterialExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

/** 物料主档维护、导入导出及内部业务查询；正式维护遵循当前实际租户与工厂。 */
public interface MdmMaterialService extends IService<MdmMaterialEntity> {

    /**
     * 在当前实际组织新增物料，统一校验参数及编码重复。
     * @param material 待建主档，不得携带原主键或其他组织身份
     * @return 写入成功为true；校验或写入失败抛出异常并回滚
     */
    boolean saveMaintained(MdmMaterialEntity material);

    /**
     * 精确更新原物料，禁止改绑编码或组织；合并原值后校验关联参数。
     * @param material 原主键及本次维护参数，null沿既有更新规则保留原值
     * @return 写入成功为true；归属、参数或写入不符时抛出异常并回滚
     */
    boolean updateMaintained(MdmMaterialEntity material);

    /**
     * 按当前租户、工厂删除所选物料。有有效换算或选中记录不齐时整批拒绝。
     * @param ids 原物料主键集合
     * @return 所选物料均删除后返回 true
     */
    boolean deleteMaintained(List<Long> ids);

    /**
     * 内部后台任务读取一个明确租户工厂的当前预警参数。
     */
    List<MdmMaterialWarningConfigDTO> warningConfigurations(Long tenantId, String factoryCode);

    /**
     * 实名/实际租户工厂内唯一物料计量观察；只读，不替调用方冻结旧历史。
     */
    MdmMaterialMeasureDTO productionMeasure(String materialCode);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   物料列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importMaterial(List<MdmMaterialExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param mdmMaterial 查询条件
     * @param ids         导出指定ID
     * @return excel 文件流
     */
    List<MdmMaterialExcelVO> export(MdmMaterialEntity mdmMaterial, Long[] ids);

    /**
     * 物料打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);
}
