package com.kitex.mom.mdm.service;

import com.github.yulichang.extension.mapping.base.MPJDeepService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmEmployeeTeamEntity;
import com.kitex.mom.mdm.entity.MdmTeamEntity;
import com.kitex.mom.mdm.vo.excel.MdmTeamExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmTeamService extends MPJDeepService<MdmTeamEntity> {

    /**
     * 深度保存班组信息(包含员工关联)
     *
     * @param mdmTeam 班组信息
     * @return 保存结果
     */
    Boolean saveDeep(MdmTeamEntity mdmTeam);

    /**
     * 深度更新班组信息(包含员工关联)
     *
     * @param mdmTeam 班组信息
     * @return 更新结果
     */
    Boolean updateDeep(MdmTeamEntity mdmTeam);

    /**
     * 深度删除班组信息(包含员工关联)
     *
     * @param ids 班组ID数组
     * @return 删除结果
     */
    Boolean removeDeep(Long[] ids);

    /**
     * 根据班组编码查询班组下的员工
     *
     * @param code 班组编码
     * @return R
     */
    List<MdmEmployeeTeamEntity> getUserByTeamCode(String code);

    List<MdmTeamExcelVO> exportTeam(MdmTeamEntity mdmTeam, Long[] ids);

    R importTeam(List<MdmTeamExcelVO> excelVOList, BindingResult bindingResult);

}