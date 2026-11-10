package com.kitex.mom.wms.service.inBill;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsInBillDetailEntity;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.NoticeStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;

import java.util.List;
import java.util.Map;

/**
 * 入库单处理器接口
 *
 * @author kitex
 * @date 2024/12/16 14:17
 */
public interface WmsInBillHandler<T extends WmsInBillEntity, E extends WmsInBillDetailEntity> {

    /**
     * 判断当前处理器是否支持指定的单据类型
     *
     * @param billType 单据类型编码 {@link InBillTypeEnum#getCode()}
     * @return true-支持，false-不支持
     */
    Boolean support(String billType);

    /**
     * 新增入库单（包含明细）
     * 新增时会自动设置状态为待提交 {@link InOutBillStateEnum#PENDING_COMMIT}
     *
     * @param inBill 入库单信息（包含明细）
     * @return true-成功，false-失败
     */
    Boolean saveDeep(T inBill);

    /**
     * 新增入库单（包含明细）
     * 新增时会自动设置状态为已通过 {@link InOutBillStateEnum#PASSED}
     *
     * @param inBill 入库单信息（包含明细）
     * @return true-成功，false-失败
     */
    Boolean addAndPass(T inBill);

    /**
     * 设置通知单默认状态
     * 默认实现为待提交状态 {@link InOutBillStateEnum#PENDING_COMMIT}
     *
     * @param inBill 入库单
     */
    void setDefaultState(WmsInBillEntity inBill);

    /**
     * 更新入库单（包含明细）
     * 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以修改
     *
     * @param inBill 入库单信息（包含明细）
     * @return true-成功，false-失败
     */
    Boolean updateDeep(T inBill);

    /**
     * 分页查询入库单列表
     *
     * @param page    分页参数
     * @param wrapper 查询条件
     * @return 分页结果
     */
    Page<T> page(Page page, QueryWrapper<WmsInBillEntity> wrapper);

    Map<String, Long> getCount(QueryWrapper<WmsInBillEntity> wrapper);


    /**
     * 分页查询入库单明细列表
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return 分页结果
     */
    Page<E> pageDetails(Page page, WmsInBillDetailQueryDTO params);

    /**
     * 根据ID查询入库单（包含明细）
     *
     * @param id 通知单ID
     * @return 入库单信息
     */
    T selectById(Long id);

    /**
     * 校验入库单数据完整性
     * 包括：必填字段、业务规则等
     *
     * @param inBill 入库单
     */
    void checkData(T inBill);

    /**
     * 更新入库单状态
     *
     * @param id    通知单ID
     * @param state 目标状态 {@link NoticeStateEnum#getCode()}
     * @return true-成功，false-失败
     */
    Boolean updateState(Long id, String state);

    /**
     * 更新入库单明细
     * 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以修改明细
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
     * 删除入库单（包含明细）
     * 仅待审核状态 {@link NoticeStateEnum#PENDING_REVIEW} 可以删除
     *
     * @param ids 通知单ID数组
     * @return true-成功，false-失败
     */
    Boolean removeDeep(String[] ids);

    /**
     * 提交入库单
     *
     * @param id 入库单id
     * @return true-成功，false-失败
     */
    Boolean commitBill(Long id);

    /**
     * 审核通过入库单
     *
     * @param id 入库单id
     * @return true-成功，false-失败
     */
    Boolean passBill(Long id);

    /**
     * 审核驳回入库单
     *
     * @param params 入库单id、原因
     * @return true-成功，false-失败
     */
    Boolean rejectBill(WmsInBillEntity params);

    /**
     * 兼容旧版移动收货。沿用历史回写方式，遇到新版采购入库单时停止办理。
     *
     * @param params 本次移动收货的原通知、物料及标准数量
     * @return 实际办理的入库单号
     */
    String setInBillNum(WmsInBillDetailAppDTO params);

    /**
     * 办理已按原号受理的移动收货。调用方须先登记永久请求、锁定通知并核实精确来源。
     *
     * @param params 经受理台账确认的原通知明细、实收数量及库位
     * @return 实际办理的入库单号
     */
    String receiveByOriginalRequest(WmsInBillDetailAppDTO params);

    /**
     * 查询主表列表
     */
    List<WmsInBillExcelVO> list(QueryWrapper<WmsInBillEntity> wrapper);

    /**
     * 查询明细列表
     */
    List<WmsInBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids);

    /**
     * 处理业务表数据
     */
    void handleDocumentBizWhenAdd(WmsInBillEntity bill);

}
