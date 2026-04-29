package com.kitex.mom.wms.service.inNotice;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.vo.WmsInNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInNoticeExcelVO;

import java.util.List;
import java.util.Map;

/**
 * 入库通知单 入库通知单整体业务功能 1. 新增通知单 2. 修改通知单 3. 获取通知单列表分页 4、获取通知单详情列表分页 5. 通知单详情（id）查询 6.
 * 更新通知单状态 7. 更新通知单详情数据 校验功能 1. 校验通知单是否已存在 2. 校验通知单状态 3. 校验仓库是否能实现该业务 4.
 * 校验通知单业务是否能支持（采购入库，退料入库，成品入库）
 */
public interface WmsInNoticeHandler<T extends WmsInNoticeEntity, E extends WmsInNoticeDetailEntity> {

    /**
     * 新增通知单
     *
     * @param wmsOutNotice 通知单信息
     * @return
     */
    Boolean saveDeep(T wmsOutNotice);

    /**
     * 设置默认状态
     *
     * @param notice
     */
    void setDefaultState(WmsInNoticeEntity notice);

    /**
     * 修改通知单
     *
     * @param wmsOutNotice 通知单信息
     * @return
     */
    Boolean updateDeep(T wmsOutNotice);

    void voidObjs(Long id);

    /**
     * 作废后的回调
     *
     * @param noticeEntity
     */
    void voidObjsBack(WmsInNoticeEntity noticeEntity);

    /**
     * 获取通知单列表分页
     *
     * @param page    分页参数
     * @param wrapper 查询条件
     * @return
     */
    Page<T> page(Page page, QueryWrapper<WmsInNoticeEntity> wrapper);

    Map<String, Long> getCount(QueryWrapper<WmsInNoticeEntity> wrapper);


    /**
     * 获取通知单详情列表分页
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return
     */
    Page<E> pageDetails(Page page, WmsInNoticeDetailQueryDTO params);

    /**
     * 通知单详情（id）查询
     *
     * @param id
     * @return
     */
    T selectById(Long id);

    /**
     * 通知单详情（id）查询-App
     *
     * @param id
     * @return
     */
    T selectByIdApp(Long id);

    /**
     * 更新通知单状态
     *
     * @param id    通知单id
     * @param state 状态
     * @return
     */
    Boolean updateState(Long id, String state);

    /**
     * 更新通知单详情数据
     *
     * @param details 通知单详情列表
     * @param id      通知单id
     * @return
     */
    Boolean updateDetails(Long id, List<E> details);

    /**
     * 校验仓库是否能实现该业务
     *
     * @param warehouseCode 仓库编码
     * @param billType      单据类型
     * @return
     */
    Boolean checkWarehouse(String warehouseCode, String billType);

    /**
     * 增加数据完整性校验
     */
    void checkData(T wmsOutNotice);

    /**
     * 支持的单据类型
     */
    Boolean support(String billType);

    Boolean removeDeep(String[] ids);

    /**
     * 查询主表列表
     */
    List<WmsInNoticeExcelVO> list(QueryWrapper<WmsInNoticeEntity> wrapper);

    /**
     * 查询明细列表
     */
    List<WmsInNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids);

}
