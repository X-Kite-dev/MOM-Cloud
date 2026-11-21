package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesMaterialListDTO;
import com.kitex.mom.mes.entity.MesMaterialListEntity;
import com.kitex.mom.mes.vo.MesMaterialListQueryVO;

import java.util.List;

/**
 * <p>
 * 用料清单主表 服务类
 * </p>
 *
 * @author CodeLens
 * @since 2025-08-27
 */
public interface MesMaterialListService extends IService<MesMaterialListEntity> {

    /**
     * 分页查询用料清单
     *
     * @param current 当前页
     * @param size    页大小
     * @param queryVO 查询条件
     * @return 分页结果
     */
    Page<MesMaterialListDTO> getMaterialListPage(Long current, Long size, MesMaterialListQueryVO queryVO);

    /**
     * 根据工单ID获取用料清单详情
     *
     * @param taskId 工单ID
     * @return 用料清单详情
     */
    MesMaterialListDTO getMaterialListByTaskId(Long taskId);

    /**
     * 根据工单创建用料清单
     *
     * @param taskId 工单ID
     * @return 创建结果
     */
    boolean createMaterialListByTask(Long taskId);

    /**
     * 更新用料清单状态
     *
     * @param id     用料清单ID
     * @param status 状态
     * @return 更新结果
     */
    boolean updateMaterialListStatus(Long id, String status);

    /**
     * 重新生成用料清单（按工单冻结BOM核对原明细）
     *
     * @param id 用料清单ID
     * @return 重新生成结果
     */
    boolean regenerateMaterialList(Long id);

    /**
     * 检查用料清单是否完成
     *
     * @param id 用料清单ID
     * @return 是否完成
     */
    boolean checkMaterialListCompleted(Long id);

    /**
     * 根据工单ID列表批量创建用料清单
     *
     * @param taskIds 工单ID列表
     * @return 创建结果
     */
    boolean batchCreateMaterialListByTasks(List<Long> taskIds);
}