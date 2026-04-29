package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import com.kitex.mom.mdm.vo.excel.MdmProcedureStationBindExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Map;

/**
 * 工序-工位绑定 Service 接口
 *
 * @author claude
 * @date 2025-07-30
 */
public interface MdmStationSettingService extends IService<MdmStationSettingEntity> {

    /**
     * 分页查询工序-工位绑定
     *
     * @param page 分页对象
     * @param bind 查询条件
     * @return 分页结果
     */
    Page<MdmStationSettingEntity> getPage(Page page, MdmStationSettingEntity bind);

    /**
     * 查询工序-工位绑定状态的数量
     *
     * @return
     */
    Map<Boolean, Long> getCount(MdmStationSettingEntity bind);

    /**
     * 根据线体ID获取工序-工位绑定列表
     *
     * @param lineId 线体ID
     * @return 绑定列表
     */
    List<MdmStationSettingEntity> getByLineId(Long lineId);

    /**
     * 根据工序编码获取对应的工位列表
     *
     * @param procedureCode 工序编码
     * @return 工位列表
     */
    List<MdmStationSettingEntity> getStationsByProcedure(String procedureCode);

    /**
     * 根据工位ID获取对应的工序列表
     *
     * @param stationId 工位ID
     * @return 工序列表
     */
    List<MdmStationSettingEntity> getProceduresByStation(Long stationId);

    /**
     * 根据车间ID获取所有工序-工位绑定
     *
     * @param workshopId 车间ID
     * @return 绑定列表
     */
    List<MdmStationSettingEntity> getByWorkshopId(Long workshopId);

    /**
     * 批量绑定工序和工位
     *
     * @param bindings 绑定列表
     * @return 是否成功
     */
    boolean batchBind(List<MdmStationSettingEntity> bindings);

    /**
     * 解绑工序和工位
     *
     * @param procedureCode 工序编码
     * @param stationId     工位ID
     * @return 是否成功
     */
    boolean unbind(String procedureCode, Long stationId);

    /**
     * 新增工序-工位绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    Boolean saveProcedureStationBind(MdmStationSettingEntity bind);

    /**
     * 更新工序-工位绑定信息
     *
     * @param bind 绑定信息
     * @return 是否成功
     */
    Boolean updateProcedureStationBind(MdmStationSettingEntity bind);

    /**
     * 获取线体的工序-工位映射关系
     *
     * @param lineId 线体ID
     * @return 工序编码 -> 工位列表的映射
     */
    Map<String, List<MdmStationSettingEntity>> getLineProcessStationMap(Long lineId);

    /**
     * 根据线体获取工位与工序对应关系
     *
     * @param lineId 线体ID
     * @return 工位ID -> 工序列表的映射
     */
    Map<Long, List<MdmStationSettingEntity>> getLineStationProcessMap(Long lineId);

    /**
     * 检查工序是否已绑定到指定工位（编辑场景排除当前记录）
     *
     * @param procedureCode 工序编码
     * @param stationId     工位ID
     * @param excludeId     排除的记录ID（编辑自身时传入）
     * @return 是否已绑定（排除 excludeId 后）
     */
    boolean isBindExists(String procedureCode, Long stationId, Long excludeId);

    /**
     * 获取工位的主工序（isPrimary=true的工序）
     *
     * @param stationId 工位ID
     * @return 主工序
     */
    MdmStationSettingEntity getPrimaryProcedureByStation(Long stationId);

    /**
     * 根据线体获取工位的主工序映射关系
     * 通过线体可以直接知道每个工位对应的主工序是什么
     *
     * @param lineId 线体ID
     * @return 工位ID -> 主工序编码的映射
     */
    Map<Long, String> getLineStationPrimaryProcedureMap(Long lineId);

    /**
     * 根据线体获取工序执行序列（按优先级排序）
     * 用于确定生产流程的工序顺序
     *
     * @param lineId 线体ID
     * @return 按优先级排序的工序编码列表
     */
    List<String> getLineProcedureSequence(Long lineId);

    /**
     * 根据线体获取工序的主工位映射关系
     * 每个工序在该线体上的主要执行工位
     *
     * @param lineId 线体ID
     * @return 工序编码 -> 主工位ID的映射
     */
    Map<String, Long> getLineProcedurePrimaryStationMap(Long lineId);

    /**
     * 导入excel 表格
     *
     * @param excelVOList   导入列表
     * @param bindingResult 错误信息列表
     * @return R
     */
    R importProcedureStationBind(List<MdmProcedureStationBindExcelVO> excelVOList, BindingResult bindingResult);

    /**
     * 导出excel 表格
     *
     * @param query 查询条件
     * @param ids   指定导出ID
     * @return excel 数据
     */
    List<MdmProcedureStationBindExcelVO> export(MdmStationSettingEntity query, Long[] ids);

    /**
     * 工位-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    Boolean employeeBind(MdmStationSettingEntity bind);

    /**
     * 设备-员工绑定
     *
     * @param bind 绑定信息
     * @return 操作结果
     */
    Boolean deviceBind(MdmStationSettingEntity bind);
}