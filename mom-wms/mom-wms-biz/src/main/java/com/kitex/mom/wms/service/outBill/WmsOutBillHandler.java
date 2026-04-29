package com.kitex.mom.wms.service.outBill;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.WmsOutBillDetailEntity;
import com.kitex.mom.wms.entity.WmsOutBillEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.NoticeStateEnum;
import com.kitex.mom.wms.enums.OutBillTypeEnum;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;

import java.util.List;
import java.util.Map;

/**
 * 入库单处理器接口
 *
 * @author kitex
 * @date 2024/12/16 14:17
 */
public interface WmsOutBillHandler<T extends WmsOutBillEntity, E extends WmsOutBillDetailEntity> {

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
    void setDefaultState(T inBill);

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
    Page<T> page(Page page, QueryWrapper<T> wrapper);

    Map<String, Long> getCount(QueryWrapper<WmsOutBillEntity> wrapper);


    /**
     * 分页查询入库单明细列表
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return 分页结果
     */
    Page<E> pageDetails(Page page, WmsInBillDetailQueryDTO params);

    /**
     * 根据ID查询入库单（包含明细、最大库存）
     *
     * @param id 通知单ID
     * @return 入库单信息
     */
    T selectById(Long id);

    /**
     * 根据ID查询入库单（包含明细）
     *
     * @param id 通知单ID
     * @return 入库单信息
     */
    T getDeepById(Long id);

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
     * @param params 出库单id、驳回原因
     * @return true-成功，false-失败
     */
    Boolean rejectBill(WmsOutBillEntity params);

    /**
     * app出库
     * 提交某一条物料数量，更新出库单出库数量或生成出库单
     */
    String setOutBillNum(WmsOutBillDetailAppDTO params);


    /**
     * 根据业务单据id查询对应的通知单主表
     */
    List<WmsOutBillEntity> outBillListByDuId(Long id);

    /**
     * 根据业务单据id删除通知单、明细（删除待提交状态和待审核状态）
     */
    Boolean outBillRemoveByDuId(Long id);

    /**
     * 查询主表列表
     */
    List<WmsOutBillExcelVO> list(QueryWrapper<WmsOutBillEntity> wrapper);

    /**
     * 查询明细列表
     */
    List<WmsOutBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids);

    /**
     * 处理业务表数据
     */
    void handleDocumentBizWhenAdd(WmsOutBillEntity bill);

}
