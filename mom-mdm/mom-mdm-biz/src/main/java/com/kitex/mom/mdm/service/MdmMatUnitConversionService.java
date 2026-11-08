package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mdm.entity.MdmMatUnitConversionEntity;
import com.kitex.mom.mdm.vo.excel.MdmMatUnitConversionExcelVO;

import java.util.List;

public interface MdmMatUnitConversionService extends IService<MdmMatUnitConversionEntity> {

    /** 新增当前租户工厂的换算，校验真实物料、正十进制比例和单位对唯一性。 */
    boolean saveMaintained(MdmMatUnitConversionEntity row);

    /** 修改原换算；身份不能改绑，有活工程引用时不能修改比例。 */
    boolean updateMaintained(MdmMatUnitConversionEntity row);

    /** 按与新增相同规则整批导入，任意一行失败全部回滚并报告行号。 */
    boolean importMaintained(List<MdmMatUnitConversionEntity> rows);

    /** 按当前组织删除原换算；活工程引用阻断，批内任一失败全部回滚。 */
    boolean deleteMaintained(List<Long> ids);

    /**
     * 导出excel 表格
     *
     * @param mdmMatUnit 查询条件
     * @param ids        导出指定ID
     * @return excel 文件流
     */
    List<MdmMatUnitConversionExcelVO> exportExcel(MdmMatUnitConversionEntity mdmMatUnit, Long[] ids);
}
