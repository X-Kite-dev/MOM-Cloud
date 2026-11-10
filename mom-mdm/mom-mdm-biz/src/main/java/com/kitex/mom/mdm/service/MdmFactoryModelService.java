package com.kitex.mom.mdm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.common.core.util.R;
import com.kitex.mom.common.barcode.dto.req.PrintReq;
import com.kitex.mom.mdm.dto.MdmFactoryModelDTO;
import com.kitex.mom.mdm.entity.MdmFactoryModelEntity;
import com.kitex.mom.mdm.vo.MdmFactoryModelAncestorVO;
import com.kitex.mom.mdm.vo.MdmFactoryModelTreeVO;
import com.kitex.mom.mdm.vo.excel.MdmFactoryModelExcelVO;
import org.springframework.validation.BindingResult;

import java.util.List;
import java.util.Set;

/**
 * 工厂建模服务接口
 *
 * @author claude
 * @date 2025-07-30
 */
public interface MdmFactoryModelService extends IService<MdmFactoryModelEntity> {

    /**
     * 根据父节点ID获取所有子节点-分页
     *
     * @param page                  分页对象，包含分页参数
     * @param mdmFactoryModelEntity 查询条件
     * @return 所有子节点
     */
    Page<MdmFactoryModelEntity> getAllChildrenPage(Page page, MdmFactoryModelEntity mdmFactoryModelEntity);

    /**
     * 获取工厂建模树形结构
     */
    List<MdmFactoryModelTreeVO> getFactoryModelTree();

    /**
     * 根据父节点ID获取子节点列表
     */
    List<MdmFactoryModelTreeVO> getChildrenByParentId(Long parentId);

    /**
     * 根据级别获取节点列表
     */
    List<MdmFactoryModelTreeVO> getNodesByLevel(Integer level);

    /**
     * 根据类型获取节点列表
     */
    List<MdmFactoryModelTreeVO> getNodesByType(String type);

    /**
     * 在当前实名用户有读取授权的工厂树中查询指定类型的启用节点。
     *
     * @param type        工厂建模节点类型
     * @param factoryCode 当前请求工厂编码，跨工厂或未授权时拒绝查询
     * @return 由真实工厂节点绑定归属的启用子节点
     */
    List<MdmFactoryModelTreeVO> getScopedNodesByType(String type, String factoryCode);

    /**
     * 根据类型获取节点列表（带缓存）
     */
    List<MdmFactoryModelTreeVO> getNodesByTypeWithCache(String type);

    /**
     * 根据类型和父节点ID获取子节点列表
     */
    List<MdmFactoryModelTreeVO> getNodesByTypeAndParent(String type, Long parentId);

    /**
     * 根据类型获取节点数量
     */
    Integer countNodesByType(String type);

    /**
     * 根据级别获取所有节点类型
     */
    List<String> getTypesByLevel(Integer level);

    /**
     * 创建工厂建模节点
     */
    Boolean createFactoryModelNode(MdmFactoryModelDTO dto);

    /**
     * 更新工厂建模节点
     */
    Boolean updateFactoryModelNode(MdmFactoryModelDTO dto);

    /**
     * 删除工厂建模节点（级联删除子节点）
     */
    Boolean deleteFactoryModelNode(Long id);

    /**
     * 移动节点位置
     */
    Boolean moveNode(Long nodeId, Long newParentId, Integer newSort);

    /**
     * 验证节点编码是否重复
     */
    Boolean validateNodeCode(String code, Long excludeId);

    /**
     * 验证节点层级关系是否正确
     */
    Boolean validateNodeHierarchy(Long parentId, Integer level, String type);

    /**
     * 获取节点的完整路径
     */
    String getNodePath(Long nodeId);

    /**
     * 根据路径查找节点
     */
    MdmFactoryModelEntity getNodeByPath(String path);

    /**
     * 获取指定节点的所有子节点ID（递归）
     */
    Set<Long> getChildrenIds(Long parentId);

    /**
     * 获取指定节点的所有子节点（递归）
     */
    List<MdmFactoryModelEntity> getAllChildren(Long parentId);

    /**
     * 导出excel 表格
     *
     * @return excel 文件流
     */
    List<MdmFactoryModelExcelVO> exportFactoryModel();

    /**
     * 导入工厂建模数据
     *
     * @param excelVOList
     * @param bindingResult
     * @return
     */
    R importFactoryModel(List<MdmFactoryModelExcelVO> excelVOList, BindingResult bindingResult);

    List<MdmFactoryModelEntity> getByTypeAndCode(String station, String workCenterCode);

    /**
     * 根据父节点ID获取子节点列表及其子节点所有上级(工序页面绑定工位，根据线体查询工位时使用)
     *
     * @param parentId 父节点ID
     * @return 子节点列表及其子节点所有上级
     */
    List<MdmFactoryModelAncestorVO> childrenByParent(Long parentId);

    /**
     * 工位打印
     *
     * @param printReq 打印Req
     * @return R 对象
     */
    String print(PrintReq printReq);

    /**
     * 根据工位ids以列表的形式展示工位及其所有上级数据
     *
     * @param stationIds 工位ids
     * @return 工位及其所有上级数据
     */
    List<MdmFactoryModelAncestorVO> getStationByAllParent(Long[] stationIds);
}
