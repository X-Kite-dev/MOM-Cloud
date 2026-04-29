package com.kitex.mom.mes.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.mes.dto.MesMaterialListDTO;
import com.kitex.mom.mes.entity.MesMaterialListEntity;
import com.kitex.mom.mes.vo.MesMaterialListQueryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 用料清单主表 Mapper 接口
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
@Mapper
public interface MesMaterialListMapper extends BaseMapper<MesMaterialListEntity> {

    /**
     * 分页查询用料清单（包含统计信息）
     *
     * @param page    分页参数
     * @param queryVO 查询条件
     * @return 用料清单分页数据
     */
    Page<MesMaterialListDTO> selectMaterialListPage(Page<MesMaterialListDTO> page, @Param("query") MesMaterialListQueryVO queryVO);

    /**
     * 根据工单ID查询用料清单详情
     *
     * @param taskId 工单ID
     * @return 用料清单详情
     */
    MesMaterialListDTO selectMaterialListByTaskId(@Param("taskId") Long taskId);

    /**
     * 根据工单ID列表查询用料清单
     *
     * @param taskIds 工单ID列表
     * @return 用料清单列表
     */
    List<MesMaterialListDTO> selectMaterialListByTaskIds(@Param("taskIds") List<Long> taskIds);

    /**
     * 统计用料清单数量按状态分组
     *
     * @param factoryCode 工厂编码
     * @return 统计结果
     */
    List<Map<String, Object>> selectMaterialListCountByStatus(@Param("factoryCode") String factoryCode);
}