package com.kitex.mom.wms.service.outNotice;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsOutNoticeEntity;
import com.kitex.mom.wms.enums.NoticeStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.vo.WmsOutNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutNoticeExcelVO;

import java.util.List;
import java.util.Map;

/**
 * 出库通知单处理器接口 定义出库通知单的核心业务操作和校验逻辑
 *
 * @param <T> 出库通知单实体类型
 * @param <E> 出库通知单明细实体类型
 * @author Cascade
 * @see NoticeStateEnum 出库通知单状态
 * @see OutBillTypeEnum 出库通知单据类型
 * @since 2024-12-16
 */
public interface WmsOutNoticeHandler<T extends WmsOutNoticeEntity, E extends WmsOutNoticeDetailEntity> {

    /**
     * 判断当前处理器是否支持指定的单据类型
     *
     * @param billType 单据类型编码 {@link OutBillTypeEnum#getCode()}
     * @return true-支持，false-不支持
     */
    Boolean support(String billType);


    /**
     * 根据通知单ID集合查询出库通知单及其明细信息
     */
    List<WmsOutNoticeEntity> getNoticeList(List<Long> ids);


    /**
     * 新增出库通知单（包含明细） 新增时会自动设置状态为待审核 {@link NoticeStateEnum#PENDING_REVIEW}
     *
     * @param wmsOutNotice 出库通知单信息（包含明细）
     * @return true-成功，false-失败
     */
    Boolean saveDeep(T wmsOutNotice);

    /**
     * 设置通知单默认状态 默认实现为待审核状态 {@link NoticeStateEnum#PENDING_REVIEW}
     *
     * @param wmsOutNotice 出库通知单
     */
    void setDefaultState(WmsOutNoticeEntity wmsOutNotice);

    /**
     * 更新出库通知单（包含明细） 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以修改
     *
     * @param wmsOutNotice 出库通知单信息（包含明细）
     * @return true-成功，false-失败
     */
    Boolean updateDeep(T wmsOutNotice);

    /**
     * 分页查询出库通知单列表
     *
     * @param page    分页参数
     * @param wrapper 查询条件
     * @return 分页结果
     */
    Page<T> page(Page page, QueryWrapper<WmsOutNoticeEntity> wrapper);


    Map<String, Long> getCount(QueryWrapper<WmsOutNoticeEntity> wrapper);


    /**
     * 分页查询出库通知单明细列表
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return 分页结果
     */
    Page<E> pageDetails(Page page, WmsInNoticeDetailQueryDTO params);

    /**
     * 根据ID查询出库通知单（包含明细）
     *
     * @param id 通知单ID
     * @return 出库通知单信息
     */
    T selectById(Long id);

    /**
     * 根据通知单id查询实时库存
     */
    WmsOutBillEntity getLiveStockByNoticeId(Long id);

    /**
     * 根据通知单id查询实时库存-移动端
     */
    WmsOutBillEntity getLiveStockByNoticeIdApp(Long id);

    /**
     * 校验出库通知单数据完整性 包括：必填字段、业务规则等
     *
     * @param wmsOutNotice 出库通知单
     */
    void checkData(T wmsOutNotice);

    /**
     * 更新出库通知单状态
     *
     * @param id    通知单ID
     * @param state 目标状态 {@link NoticeStateEnum#getCode()}
     * @return true-成功，false-失败
     */
    Boolean updateState(Long id, String state);

    /**
     * 更新出库通知单明细 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以修改明细
     *
     * @param id      通知单ID
     * @param details 明细列表
     * @return true-成功，false-失败
     */
    Boolean updateDetails(Long id, List<E> details);

    /**
     * 校验仓库是否支持当前业务类型
     *
     * @param warehouseCode 仓库编码
     * @param billType      单据类型 {@link OutBillTypeEnum#getCode()}
     * @return true-支持，false-不支持
     */
    Boolean checkWarehouse(String warehouseCode, String billType);

    /**
     * 删除出库通知单（包含明细） 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以删除
     *
     * @param ids 通知单ID数组
     * @return true-成功，false-失败
     */
    Boolean removeDeep(String[] ids);

    /**
     * 作废单据
     *
     * @param id 通知单id
     */
    void voidObjs(Long id);

    /**
     * 根据业务单据id查询对应的通知单主表
     */
    List<WmsOutNoticeEntity> outNoticeListByDuId(Long id);


    /**
     * 根据业务单据id作废 待处理，处理中的通知单主表
     */
    Boolean outNoticeRemoveByDuId(Long id);

    /**
     * 查询主表列表
     */
    List<WmsOutNoticeExcelVO> list(QueryWrapper<WmsOutNoticeEntity> wrapper);

    /**
     * 查询明细列表
     */
    List<WmsOutNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids);

    /**
     * 作废生产领料出库通知单-通过业务单据
     */
    Boolean voidObjByDocumentId(Long documentId, String documentTable);

}
