package com.kitex.mom.mdm.utils;

import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.entity.MdmStationSettingEntity;
import com.kitex.mom.mdm.service.MdmFactoryModelService;
import com.kitex.mom.mdm.service.MdmStationSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工序-工位绑定迁移工具类
 * 为使用旧工作中心模式的业务模块提供迁移帮助
 *
 * @author claude
 * @date 2025-07-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcedureStationMigrationUtil {

    private final MdmStationSettingService procedureStationBindService;
    private final MdmFactoryModelService factoryModelService;

    /**
     * 根据工作中心编码获取对应的工位ID列表
     * 用于将旧的工作中心引用迁移到新的工位引用
     *
     * @param workCenterCode 工作中心编码
     * @return 对应的工位ID列表
     */
    public List<Long> getStationIdsByWorkCenterCode(String workCenterCode) {
        // 在工厂建模中查找类型为STATION且编码匹配的节点
        List<MdmFactoryModelEntity> stations = factoryModelService.getByTypeAndCode("STATION", workCenterCode);
        return stations.stream()
                .map(MdmFactoryModelEntity::getId)
                .collect(Collectors.toList());
    }

    /**
     * 根据工序编码获取其在指定线体上的工位分配
     * 替代原有的工序-工作中心查询逻辑
     *
     * @param procedureCode 工序编码
     * @param lineId        线体ID（可选，为null时查询所有线体）
     * @return 工位ID列表
     */
    public List<Long> getStationsByProcedureAndLine(String procedureCode, Long lineId) {
        List<MdmStationSettingEntity> bindings;

        if (lineId != null) {
            // 查询指定线体上的工序绑定
            bindings = procedureStationBindService.getByLineId(lineId).stream()
                    .filter(bind -> procedureCode.equals(bind.getProcedureCode()))
                    .collect(Collectors.toList());
        } else {
            // 查询所有工序绑定
            bindings = procedureStationBindService.getStationsByProcedure(procedureCode);
        }

        return bindings.stream()
                .map(MdmStationSettingEntity::getStationId)
                .collect(Collectors.toList());
    }

    /**
     * 获取工位的主要工序编码
     * 替代原有的工作中心工序查询逻辑
     *
     * @param stationId 工位ID
     * @return 主要工序编码，可能为null
     */
    public String getPrimaryProcedureByStation(Long stationId) {
        MdmStationSettingEntity primaryBind = procedureStationBindService.getPrimaryProcedureByStation(stationId);
        return primaryBind != null ? primaryBind.getProcedureCode() : null;
    }

    /**
     * 批量获取工位的主要工序映射
     * 用于批量数据处理和性能优化
     *
     * @param stationIds 工位ID列表
     * @return 工位ID -> 主要工序编码的映射
     */
    public Map<Long, String> batchGetPrimaryProceduresByStations(List<Long> stationIds) {
        return stationIds.stream()
                .collect(Collectors.toMap(
                        stationId -> stationId,
                        this::getPrimaryProcedureByStation,
                        (existing, replacement) -> existing
                )).entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * 根据线体获取完整的工序-工位映射关系
     * 为MES等业务模块提供统一的数据查询接口
     *
     * @param lineId 线体ID
     * @return 工序编码 -> 工位ID列表的映射
     */
    public Map<String, List<Long>> getLineProcedureStationMapping(Long lineId) {
        Map<String, List<MdmStationSettingEntity>> procedureStationMap =
                procedureStationBindService.getLineProcessStationMap(lineId);

        return procedureStationMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(MdmStationSettingEntity::getStationId)
                                .collect(Collectors.toList())
                ));
    }

    /**
     * 根据线体获取工位-工序映射关系
     * 为设备监控等业务模块提供统一的数据查询接口
     *
     * @param lineId 线体ID
     * @return 工位ID -> 工序编码列表的映射
     */
    public Map<Long, List<String>> getLineStationProcedureMapping(Long lineId) {
        Map<Long, List<MdmStationSettingEntity>> stationProcedureMap =
                procedureStationBindService.getLineStationProcessMap(lineId);

        return stationProcedureMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(MdmStationSettingEntity::getProcedureCode)
                                .collect(Collectors.toList())
                ));
    }

    /**
     * 检查工序和工位的绑定关系是否存在
     * 替代原有的工序-工作中心关系检查
     *
     * @param procedureCode 工序编码
     * @param stationId     工位ID
     * @return 是否存在绑定关系
     */
    public boolean isProcedureStationBound(String procedureCode, Long stationId) {
        return procedureStationBindService.isBindExists(procedureCode, stationId, null);
    }

    /**
     * 获取线体的工序执行顺序
     * 为生产排程提供标准的工序流程
     *
     * @param lineId 线体ID
     * @return 按优先级排序的工序编码列表
     */
    public List<String> getLineProcedureSequence(Long lineId) {
        return procedureStationBindService.getLineProcedureSequence(lineId);
    }

    /**
     * 根据工序和线体获取推荐的工位
     * 优先返回主工位，如果没有主工位则返回第一个可用工位
     *
     * @param procedureCode 工序编码
     * @param lineId        线体ID
     * @return 推荐的工位ID，可能为null
     */
    public Long getRecommendedStationForProcedure(String procedureCode, Long lineId) {
        // 先尝试获取该工序在该线体上的主工位
        Map<String, Long> procedurePrimaryStationMap =
                procedureStationBindService.getLineProcedurePrimaryStationMap(lineId);

        Long primaryStationId = procedurePrimaryStationMap.get(procedureCode);
        if (primaryStationId != null) {
            return primaryStationId;
        }

        // 如果没有主工位，返回第一个可用工位
        List<Long> availableStations = getStationsByProcedureAndLine(procedureCode, lineId);
        return availableStations.isEmpty() ? null : availableStations.get(0);
    }

    /**
     * 日志记录迁移过程中的关键信息
     *
     * @param message 日志消息
     * @param params  参数
     */
    private void logMigration(String message, Object... params) {
        log.info("[工序工位迁移] " + message, params);
    }
}