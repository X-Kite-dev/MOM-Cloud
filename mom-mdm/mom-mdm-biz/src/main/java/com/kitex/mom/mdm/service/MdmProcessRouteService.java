package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.dto.*;
import com.kitex.mom.mdm.entity.MdmProcessProcedureMatEntity;
import com.kitex.mom.mdm.entity.MdmProcessProcedureSettingEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteEntity;
import com.kitex.mom.mdm.entity.MdmProcessRouteWorkingEntity;
import com.kitex.mom.mdm.vo.MdmProcedureSopVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteProcedureVO;
import com.kitex.mom.mdm.vo.MdmProcessRouteTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmProcessRouteExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;

public interface MdmProcessRouteService extends IService<MdmProcessRouteEntity> {

    /**
     * 通过id删除工艺路线
     *
     * @param ids id列表
     * @return R
     */
    Boolean removeRoute(List<Long> ids);

    /**
     * 通过id查询工艺路线
     *
     * @param id id
     * @return R
     */
    MdmProcessRouteDTO getRouteById(Long id);

    /**
     * 通过ids查询工艺路线集合
     *
     * @param ids id
     * @return R
     */
    List<MdmProcessRouteDTO> getRouteByIds(List<Long> ids);

    /**
     * 通过id查询工艺路线
     *
     * @param id id
     * @return R
     */
    MdmProcessRouteDTO getRouteByIdAndLineCode(Long id, String lineCode);

    /**
     * 导出excel 表格
     *
     * @param mdmProcessRoute 查询条件
     * @param ids             导出指定ID
     * @return excel 文件流
     */
    List<MdmProcessRouteExcelVO> export(MdmProcessRouteEntity mdmProcessRoute, Long[] ids);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   工艺路线列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importProcessRoute(List<MdmProcessRouteExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 组件传参
     *
     * @param page
     * @param mdmProcessRoute
     * @return
     */
    Page<MdmProcessRouteEntity> selectPage(Page page, MdmProcessRouteSelectDTO mdmProcessRoute);

    /**
     * 根据工艺路线ID查询工艺路线下的工序及工序下的工作中心
     *
     * @param routeId 工艺路线ID
     * @return R
     */
    MdmProcessRouteProcedureVO getProcessWorking(Long routeId);

    /**
     * 查询工艺路线详情
     *
     * @param id
     * @return
     */
    MdmProcessRouteWorkingEntity getProcessWorkingById(Long id);


    /**
     * 新增工艺路线下的工序
     *
     * @param mdmProcessRouteProcedureDTO 工艺路线-工序DTO
     * @return R
     */
    Boolean saveProcessWorking(MdmProcessRouteProcedureDTO mdmProcessRouteProcedureDTO);

    /**
     * 根据工艺路线编码查询工序列表
     *
     * @param routeCode 工艺路线编码
     * @return R
     */
    List<MdmProcessRouteWorkingEntity> getProcedureList(String routeCode);

    /**
     * 新增工艺路线下的工序的SOP
     *
     * @param mdmProcessProcedureSopDTO 工艺路线-工序-SOP
     * @return R
     */
    Boolean saveSop(MdmProcessProcedureSopDTO mdmProcessProcedureSopDTO);

    /**
     * 查询工艺路线下的工序的SOP
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    List<MdmProcedureSopVO> getSop(Long id);

    /**
     * 查询工艺路线下工序绑定的投料信息
     *
     * @param id 工艺路线-工序id
     * @return R
     */
    List<MdmProcessProcedureMatEntity> getMat(Long id);

    /**
     * 新增工艺路线下工序绑定的投料信息
     *
     * @param mdmProcessProcedureMatDTO 工艺路线-工序-投料
     * @return R
     */
    Boolean saveMat(MdmProcessProcedureMatDTO mdmProcessProcedureMatDTO);

    /**
     * 工艺路线树查询
     *
     * @param queryWrapper 查询条件
     * @return
     */
    List<MdmProcessRouteTreeVO> tree(QueryWrapper<MdmProcessRouteEntity> queryWrapper);

    /**
     * 根据工艺路线、工序编码查询工序的SOP
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工艺路线-编码
     * @return
     */
    List<MdmProcedureSopVO> getSopByCode(String routeCode, String processCode);

    /**
     * 根据工艺路线、工序编码查询工序的投料信息
     *
     * @param routeCode   工艺路线-编码
     * @param processCode 工序-编码
     * @return
     */
    List<MdmProcessProcedureMatEntity> getMatByCode(String routeCode, String processCode);

    /**
     * 根据id设置默认工艺路线
     *
     * @param id id
     * @return R
     */
    Boolean routeDefault(Long id);

    /**
     * 查询工艺路线下工序的设置信息
     *
     * @param routeCode   工艺路线编码
     * @param processCode 工序编码
     * @return R
     */
    MdmProcessProcedureSettingEntity getSetByCode(String routeCode, String processCode);

    /**
     * 修改工艺路线下工序的设置信息
     *
     * @param mdmProcessProcedureSetting 工艺路线-工序设置
     * @return R
     */
    Boolean saveOrUpdateSet(MdmProcessProcedureSettingEntity mdmProcessProcedureSetting);
}