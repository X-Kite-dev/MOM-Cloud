package com.kitex.mom.wms.service.outBill;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.common.core.util.R;
import com.kitex.common.security.util.SecurityUtils;
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsOutBillDetailAppDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsDeliverGoodsBaseService;
import com.kitex.mom.wms.service.WmsLiveStockService;
import com.kitex.mom.wms.service.WmsPickingAreaService;
import com.kitex.mom.wms.vo.WmsOutBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutBillExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 出库单处理器抽象实现
 * 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的出库单处理
 *
 * @author kitex
 * @since 2024-12-16 14:28:29
 */

public abstract class AbstractWmsOutBillHandler implements WmsOutBillHandler<WmsOutBillEntity, WmsOutBillDetailEntity> {

    @Autowired
    protected WmsOutBillMapper wmsOutBillMapper;
    @Autowired
    protected WmsOutBillDetailMapper wmsOutBillDetailMapper;

    @Autowired
    protected WmsOutNoticeMapper wmsOutNoticeMapper;
    @Autowired
    protected WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    @Autowired
    private WmsLiveStockService wmsLiveStockService;
    @Autowired
    private WmsPickingAreaService wmsPickingAreaService;
    @Autowired
    private WmsInoutFlowMapper wmsInoutFlowMapper;

    @Autowired
    private WmsDeliverGoodsBaseService wmsDeliverGoodsBaseService;

    @Autowired
    private RemoteSeqConfigService remoteSeqConfigService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsOutBillEntity entity) {
        // 1、保存前的校验和处理
        beforeSaveDeep(entity);

        // 2、执行保存
        doSaveDeep(entity);

        return Boolean.TRUE;
    }

    /**
     * 保存前的处理
     *
     * @param entity 出库单
     */
    public void beforeSaveDeep(WmsOutBillEntity entity) {
        // 1. 校验数据完整性
        checkData(entity);

        // 2. 校验仓库是否支持该业务
        checkWarehouse(entity.getWarehouseCode(), entity.getBillType());

        // 3. 设置默认状态
        setDefaultState(entity);

        // 4. 设置初始数据
        if (StrUtil.isBlank(entity.getBillNo())) {
            R<String> wms_out_bill_R = remoteSeqConfigService.create("wms_out_bill");
            if (!wms_out_bill_R.isOk()) {
                throw new CheckedException("生成出库单号失败！" + wms_out_bill_R.getMsg());
            }
            String billNo = wms_out_bill_R.getData();
            entity.setBillNo(billNo);
        }

        entity.setDetailFinishNum(0d);
        entity.setDetailNotFinishNum(Double.parseDouble(String.valueOf(entity.getDetails().size())));
    }

    /**
     * 执行保存单据
     *
     * @param entity 出库单
     * @return 通知单未出库总数
     */
    public Double doSaveDeep(WmsOutBillEntity entity) {
        // 1、 保存主表数据
        int result = wmsOutBillMapper.insert(entity);
        if (result <= 0) {
            throw new CheckedException("保存出库单失败");
        }

        // 2、 保存明细数据
        List<WmsOutBillDetailEntity> detailList = entity.getDetails();
        if (CollUtil.isNotEmpty(detailList)) {
            List<WmsOutBillDetailEntity> list = detailList.stream().peek(detail ->
                    detail.setBillId(entity.getId())
                            .setFactoryCode(entity.getFactoryCode())
                            .setWarehouseCode(entity.getWarehouseCode())
                            .setWarehouseName(entity.getWarehouseName())
            ).toList();
            wmsOutBillDetailMapper.insert(list);
        }

        // 3、更新通知单数量
        return createBillHandleNoticeOutNum(entity);
    }

    /**
     * 新增出库单并审核通过
     *
     * @param outBill 出库单信息（包含明细）
     * @return true-成功，false-失败
     */
    @Override
    @Transactional
    public Boolean addAndPass(WmsOutBillEntity outBill) {
        // 1、保存前的处理
        beforeSaveDeep(outBill);
        // 2、设置状态、明细数、出库时间
        LocalDateTime now = LocalDateTime.now();
        List<WmsOutBillDetailEntity> details = outBill.getDetails();
        outBill.setState(InOutBillStateEnum.PASSED.getCode());
        outBill.setDetailFinishNum(Double.parseDouble(String.valueOf(details.size())));
        outBill.setDetailNotFinishNum(0d);
        outBill.setOutTime(now);
        outBill.setReviewer(SecurityUtils.getUser().getUsername());
        outBill.setReviewTime(now);
        // 3、执行保存
        doSaveDeep(outBill);
        // 4、判断通知单物料是否已全部完成出库
        passBillHandleNoticeState(outBill);
        // 5、增加实时库存、生成出库流水记录
        afterOutStock(outBill);

        // 6、对documentId的单据进行操作
        handleDocumentBizWhenAdd(outBill);

        try {
            //如果来源于发货，更新发货数据
            if (DocumentEnum.DELIVER.getCode().equals(outBill.getDocumentTable())) {
                wmsDeliverGoodsBaseService.updateDeliverByBill(outBill);
            }
        } catch (Exception e) {
            throw new CheckedException("更新发货信息失败！原因：" + e.getMessage(), e);
        }

        return Boolean.TRUE;
    }

    @Override
    public void setDefaultState(WmsOutBillEntity entity) {
        // 默认状态为待提交
        entity.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(WmsOutBillEntity entity) {
        // 1. 校验数据完整性
        checkData(entity);

        // 2. 校验当前状态是否允许修改
        WmsOutBillEntity existingNotice = wmsOutBillMapper.selectById(entity.getId());
        Assert.notNull(existingNotice, "出库单不存在");

        NoticeStateEnum currentState = NoticeStateEnum.getByCode(existingNotice.getState());
        Assert.notNull(currentState, "出库单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");

        // 3. 更新主表数据
        int result = wmsOutBillMapper.updateById(entity);
        if (result <= 0) {
            throw new CheckedException("更新出库单失败");
        }

        // 4. 更新明细数据
        if (CollUtil.isNotEmpty(entity.getDetails())) {
            updateDetails(entity.getId(), entity.getDetails());
        }

        return Boolean.TRUE;
    }

    @Override
    public Page<WmsOutBillEntity> page(Page page, QueryWrapper<WmsOutBillEntity> wrapper) {
        return wmsOutBillMapper.selectPage(page, wrapper);
    }


    @Override
    public Map<String, Long> getCount(QueryWrapper<WmsOutBillEntity> wrapper) {
        return wmsOutBillMapper.selectList(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsOutBillEntity::getState, Collectors.counting()));
    }

    @Override
    public Page<WmsOutBillDetailEntity> pageDetails(Page page, WmsInBillDetailQueryDTO params) {
        return wmsOutBillDetailMapper.fetchDetails(page, params, null);
    }

    @Override
    public WmsOutBillEntity selectById(Long id) {
        // 1. 查询主表数据
        WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
        if (notice == null) {
            return null;
        }

        // 2. 查询明细数据
        List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper.selectList(
                Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                        .eq(WmsOutBillDetailEntity::getBillId, id)
        );
        // 3. 查找库存量
        List<WmsLiveStockEntity> liveStocks = wmsLiveStockService.list(
                new QueryWrapper<WmsLiveStockEntity>()
                        .select("mat_code", "supplier_code", "mat_batch", "container_code", "site_code", "sum(mat_num) as mat_num")
                        .eq("warehouse_code", notice.getWarehouseCode())
                        .eq("status", 0)
                        .groupBy("mat_code", "supplier_code", "mat_batch", "container_code", "site_code")
                        .orderByAsc("supplier_code", "mat_batch", "site_code", "container_code")
        );
        Map<String, Double> map = liveStocks.stream().collect(Collectors.toMap(
                it -> it.getMatCode() + it.getSupplierCode() + it.getMatBatch() + it.getContainerCode() + it.getSiteCode()
                , WmsLiveStockEntity::getMatNum));
        details.forEach(item -> {
            Double num = Optional.ofNullable(map.get(item.getMatCode() + item.getSupplierCode() + item.getMatBatch() + item.getContainerCode() + item.getSiteCode())).orElse(0d);
            item.setMaxOutNum(num);
        });

        notice.setDetails(details);

        return notice;
    }

    @Override
    public WmsOutBillEntity getDeepById(Long id) {
        // 1. 查询主表数据
        WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
        if (notice == null) {
            return null;
        }
        // 2. 查询明细数据
        List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper.selectList(
                Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                        .eq(WmsOutBillDetailEntity::getBillId, id)
        );
        notice.setDetails(details);

        return notice;
    }

    @Override
    public void checkData(WmsOutBillEntity entity) {
        // 1. 校验必填数据
        if (CollUtil.isEmpty(entity.getDetails())) {
            throw new CheckedException("明细数据不能为空");
        }
        Optional.ofNullable(entity.getWarehouseCode()).orElseThrow(() -> new CheckedException("仓库编码不能为空"));
        Optional.ofNullable(entity.getBillType()).orElseThrow(() -> new CheckedException("单据类型不能为空"));

        // 2. 校验单据类型是否有效
        Assert.isTrue(OutBillTypeEnum.isValid(entity.getBillType()) || OtherOutBillTypeEnum.isValid(entity.getBillType()), "无效的单据类型：" + entity.getBillType());
    }

    @Override
    public Boolean updateState(Long id, String state) {
        Assert.notNull(id, "通知单ID不能为空");

        // 校验状态是否有效
        NoticeStateEnum targetState = NoticeStateEnum.getByCode(state);
        Assert.notNull(targetState, "无效的目标状态：" + state);

        int result = wmsOutBillMapper.update(null,
                Wrappers.<WmsOutBillEntity>lambdaUpdate()
                        .eq(WmsOutBillEntity::getId, id)
                        .set(WmsOutBillEntity::getState, state));

        return result > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDetails(Long id, List<WmsOutBillDetailEntity> details) {
        // 1. 校验通知单状态
        WmsOutBillEntity notice = wmsOutBillMapper.selectById(id);
        Assert.notNull(notice, "出库单不存在");

        InOutBillStateEnum currentState = InOutBillStateEnum.getByCode(notice.getState());
        Assert.notNull(currentState, "出库单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改明细");

        // 2. 删除原有明细
        wmsOutBillDetailMapper.delete(
                Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                        .eq(WmsOutBillDetailEntity::getBillId, id)
        );

        // 3. 插入新明细
        if (CollUtil.isNotEmpty(details)) {
            List<WmsOutBillDetailEntity> list = details.stream()
                    .peek(item -> item.setBillId(id).setId(null))
                    .toList();
            wmsOutBillDetailMapper.insert(list);
        }

        return Boolean.TRUE;
    }

    @Override
    public Boolean checkWarehouse(String warehouseCode, String billType) {
        // TODO: 实现仓库业务类型校验逻辑
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(String[] ids) {
        if (CollUtil.isEmpty(Arrays.asList(ids))) {
            return Boolean.TRUE;
        }

        // 1. 校验出库单状态
        List<WmsOutBillEntity> entities = wmsOutBillMapper.selectBatchIds(Arrays.asList(ids));
        for (WmsOutBillEntity entity : entities) {
            InOutBillStateEnum state = InOutBillStateEnum.getByCode(entity.getState());
            Assert.notNull(state, "出库单状态无效");
            Assert.isTrue(state.isEditable(),
                    String.format("出库单[%s]当前状态[%s]不允许删除", entity.getBillNo(), state.getDesc()));
        }

        // 2. 回退通知单的数量
        for (WmsOutBillEntity entity : entities) {
            List<WmsOutBillDetailEntity> details = wmsOutBillDetailMapper.selectList(Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                    .eq(WmsOutBillDetailEntity::getBillId, entity.getId()));
            entity.setDetails(details);
            removeBillHandleNoticeOutNum(entity);
        }

        // 2. 删除主表数据
        wmsOutBillMapper.deleteByIds(Arrays.asList(ids));

        // 3. 删除明细数据
        wmsOutBillDetailMapper.delete(
                Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                        .in(WmsOutBillDetailEntity::getBillId, ids)
        );

        return Boolean.TRUE;
    }

    /**
     * 提交出库单
     *
     * @param id 出库单id
     * @return true-成功，false-失败
     */
    @Override
    public Boolean commitBill(Long id) {
        // 1. 校验出库单状态
        WmsOutBillEntity bill = wmsOutBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "出库单状态无效");
        Assert.isTrue(state.isEditable(), String.format("出库单[%s]当前状态[%s]不允许提交", bill.getBillNo(), state.getDesc()));
        // 2. 更新状态为待审核
        wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
                .set(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * 审核通过出库单
     *
     * @param id 出库单id
     * @return true-成功，false-失败
     */
    @Override
    @Transactional
    public Boolean passBill(Long id) {
        // 1. 校验出库单状态
        WmsOutBillEntity bill = wmsOutBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "出库单状态无效");
        Assert.isTrue(state.isReviewable(), String.format("出库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));

        // 3. 更新状态为已通过
        List<WmsOutBillDetailEntity> billDetails = wmsOutBillDetailMapper.selectList(
                Wrappers.<WmsOutBillDetailEntity>lambdaQuery()
                        .eq(WmsOutBillDetailEntity::getBillId, id)
        );
        bill.setDetails(billDetails);
        // 修改出库单明细数、出库时间和状态
        LocalDateTime now = LocalDateTime.now();
        wmsOutBillMapper.update(Wrappers.<WmsOutBillEntity>lambdaUpdate()
                .set(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .set(WmsOutBillEntity::getDetailFinishNum, billDetails.size())
                .set(WmsOutBillEntity::getDetailNotFinishNum, 0.0)
                .set(WmsOutBillEntity::getOutTime, now)
                .set(WmsOutBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
                .set(WmsOutBillEntity::getReviewTime, now)
                .eq(BaseEntity::getId, id)
        );

        // 2. 更新通知单状态   判断通知单物料是否已全部完成出库
        passBillHandleNoticeState(bill);

        // 4、修改实时库存、生成出库流水记录
        afterOutStock(bill);

        // 5、对documentId的单据进行操作
        handleDocumentBizWhenAdd(bill);


        try {
            //如果来源于发货，更新发货数据
            if (DocumentEnum.DELIVER.getCode().equals(bill.getDocumentTable())) {
                wmsDeliverGoodsBaseService.updateDeliverByBill(bill);
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }

        return Boolean.TRUE;
    }

    /**
     * 创建出库单时修改通知单的出库数
     *
     * @param bill 出库单
     * @return 通知单未出库总数
     */
    public Double createBillHandleNoticeOutNum(WmsOutBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return 0d;
        }
        List<WmsOutBillDetailEntity> billDetails = bill.getDetails();
        // 判断是否超出通知出库总数
        WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("出库通知单查询失败"));
        List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(
                Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
                        .eq(WmsOutNoticeDetailEntity::getNoticeId, bill.getNoticeId())
        );
        Map<Long, WmsOutNoticeDetailEntity> map = noticeDetails.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        // 记录修改过的通知单明细，因为可能多条出库单明细对应一条通知单明细 (之前出库是具体的库存记录，现在是库存汇总，也可保留这个思路)
        Set<Long> updatedSet = new HashSet<>();
        Double totalNum = 0.0;
        for (WmsOutBillDetailEntity billDetail : billDetails) {
            WmsOutNoticeDetailEntity noticeDetail = Optional.ofNullable(map.get(billDetail.getNoticeDetailId())).orElseThrow(() -> new CheckedException("出库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败！物料编码：[" + billDetail.getMatCode() + "]"));
            Double billNum = Optional.ofNullable(billDetail.getMatNum()).orElseThrow(() -> new CheckedException("出库单明细标准单位数量为空"));
            Double noticeNum = Optional.ofNullable(noticeDetail.getNotOutNum()).orElseThrow(() -> new CheckedException("出库通知单明细通知未出库量为空"));
            if (billNum > noticeNum) {
                throw new CheckedException(String.format("物料编码为[%s]的物料超出通知出库量！", billDetail.getMatCode()));
            }
            // 修改通知单明细的出库数
            noticeDetail.setNotOutNum(noticeDetail.getNotOutNum() - billNum);
            noticeDetail.setOutNum(noticeDetail.getOutNum() + billNum);
            updatedSet.add(billDetail.getNoticeDetailId());

            // 累计出库总数
            totalNum += billNum;
        }
        // 修改通知单明细
        for (Long noticeDetailId : updatedSet) {
            WmsOutNoticeDetailEntity handDetail = map.get(noticeDetailId);
            wmsOutNoticeDetailMapper.update(Wrappers.<WmsOutNoticeDetailEntity>lambdaUpdate()
                    .set(WmsOutNoticeDetailEntity::getNotOutNum, handDetail.getNotOutNum())
                    .set(WmsOutNoticeDetailEntity::getOutNum, handDetail.getOutNum())
                    .eq(BaseEntity::getId, noticeDetailId)
            );
        }

        // 修改通知单总数量
        wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
                .set(WmsOutNoticeEntity::getAllNotOutNum, notice.getAllNotOutNum() - totalNum)
                .set(WmsOutNoticeEntity::getAllOutNum, notice.getAllOutNum() + totalNum)
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())
                .eq(BaseEntity::getId, notice.getId())
        );
        return notice.getAllNotOutNum() - totalNum;
    }

    /**
     * 删除出库单时修改通知单的出库数
     *
     * @param bill 出库单
     */
    public void removeBillHandleNoticeOutNum(WmsOutBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return;
        }
        List<WmsOutBillDetailEntity> billDetails = bill.getDetails();
        // 查找通知单
        WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("出库通知单查询失败"));
        List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(
                Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery()
                        .eq(WmsOutNoticeDetailEntity::getNoticeId, bill.getNoticeId())
        );
        Map<Long, WmsOutNoticeDetailEntity> map = noticeDetails.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        // 记录修改过的通知单明细，因为可能多条出库单明细对应一条通知单明细 (之前出库是具体的库存记录，现在是库存汇总，也可保留这个思路)
        Set<Long> updatedSet = new HashSet<>();
        Double totalNum = 0.0;
        for (WmsOutBillDetailEntity billDetail : billDetails) {
            WmsOutNoticeDetailEntity noticeDetail = map.get(billDetail.getNoticeDetailId());
            if (noticeDetail == null) {
                continue;
            }
            Double billNum = Optional.ofNullable(billDetail.getMatNum()).orElse(0d);
            Double notOutNum = Optional.ofNullable(noticeDetail.getNotOutNum()).orElse(0d);
            Double outNum = Optional.ofNullable(noticeDetail.getOutNum()).orElse(0d);
            // 修改通知单明细的出库数
            noticeDetail.setNotOutNum(notOutNum + billNum);
            noticeDetail.setOutNum(outNum - billNum);
            updatedSet.add(billDetail.getNoticeDetailId());

            // 累计出库总数
            totalNum += billNum;
        }
        // 修改通知单明细
        for (Long noticeDetailId : updatedSet) {
            WmsOutNoticeDetailEntity handDetail = map.get(noticeDetailId);
            wmsOutNoticeDetailMapper.update(Wrappers.<WmsOutNoticeDetailEntity>lambdaUpdate()
                    .set(WmsOutNoticeDetailEntity::getNotOutNum, handDetail.getNotOutNum())
                    .set(WmsOutNoticeDetailEntity::getOutNum, handDetail.getOutNum())
                    .eq(BaseEntity::getId, noticeDetailId)
            );
        }

        // 修改通知单总数量
        wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
                .set(WmsOutNoticeEntity::getAllNotOutNum, notice.getAllNotOutNum() + totalNum)
                .set(WmsOutNoticeEntity::getAllOutNum, notice.getAllOutNum() - totalNum)
                // 如果入库数变成0了  状态改为待处理，>0是处理中
                .set(WmsOutNoticeEntity::getState, notice.getAllOutNum() - totalNum <= 0 ? InOutNoticeStateEnum.READY.getCode() : InOutNoticeStateEnum.RUNNING.getCode())
                .eq(BaseEntity::getId, notice.getId())
        );
    }

    /**
     * 出库时修改通知单的状态
     * 当通知单内的物料全部出库之后设为已完成（出库单全部完成并且通知单未出库数量为0）
     *
     * @param bill 出库单
     */
    public void passBillHandleNoticeState(WmsOutBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return;
        }
        // 查找还有没有未完成的出库单
        List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getNoticeId, bill.getNoticeId())
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode()) // pc不等于已通过
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode()) // app不等于已完成
        );
        if (outBills.size() == 0) {
            // 判断通知单未入库数是否为0
            WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("出库通知单查询失败"));
            if (notice.getAllNotOutNum() <= 0) {
                // 修改通知单状态
                wmsOutNoticeMapper.update(Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
                        .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.FINISHED.getCode())
                        .set(WmsOutNoticeEntity::getCompleteTime, LocalDateTime.now())
                        .eq(BaseEntity::getId, notice.getId())
                );
            }
        }
    }

    /**
     * 出库通过之后
     * 修改实时库存，生成出库流水
     */
    @Transactional
    public void afterOutStock(WmsOutBillEntity outBill) {
        List<WmsOutBillDetailEntity> details = outBill.getDetails();
        for (WmsOutBillDetailEntity detail : details) {
            // 处理实时库存  由物料编码、供应商编码、批次、容器码、库位 确定出库的物料
            wmsLiveStockService.reduceStockWhenOut(detail);
            // 出库流水
            WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
            BeanUtil.copyProperties(detail, inoutFlow, "id");
            inoutFlow.setBillNo(outBill.getBillNo());
            inoutFlow.setBillType(outBill.getBillType());
            inoutFlow.setOperateType(InOutEnum.OUT.getCode());
            wmsInoutFlowMapper.insert(inoutFlow);
        }

    }

    /**
     * 审核驳回出库单
     *
     * @param params 出库单id、驳回原因
     * @return true-成功，false-失败
     */
    @Override
    public Boolean rejectBill(WmsOutBillEntity params) {
        Long id = Optional.ofNullable(params.getId()).orElseThrow(() -> new CheckedException("出库单id为空"));
        // 1. 校验出库单状态
        WmsOutBillEntity bill = wmsOutBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "出库单状态无效");
        Assert.isTrue(state.isReviewable(), String.format("出库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));
        // 2. 更新状态为待提交
        wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
                .set(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())
                .set(WmsOutBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
                .set(WmsOutBillEntity::getReviewTime, LocalDateTime.now())
                .set(WmsOutBillEntity::getRejectReason, params.getRejectReason())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * app出库
     * 提交某一条物料数量，更新出库单出库数量或生成出库单
     * 将物料从库存放到拣货区
     * <p>
     * 根据通知单号、物料编码、批次、供应商查找通知单明细；
     * 查找有没有出库单，查找出库单明细有没有这个物料；
     * <p>
     * 操作数量的赋值有待商榷
     *
     * @param params 物料数量等
     * @return R  对象
     */
    @Override
    @Transactional
    public String setOutBillNum(WmsOutBillDetailAppDTO params) {
        // 查找通知单
        WmsOutNoticeEntity notice = Optional.ofNullable(wmsOutNoticeMapper.selectById(params.getNoticeId())).orElseThrow(() -> new CheckedException("通知单查找失败！"));
        if (StrUtil.equals(notice.getState(), InOutNoticeStateEnum.FINISHED.getCode()) || StrUtil.equals(notice.getState(), InOutNoticeStateEnum.CANCELED.getCode())) {
            throw new CheckedException(String.format("该通知单%s，无法进行出库操作！", InOutNoticeStateEnum.getByCode(notice.getState()).getName()));
        }
        // 通过物料查询通知单明细
        List<WmsOutNoticeDetailEntity> noticeDetails = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                .eq(WmsOutNoticeDetailEntity::getNoticeId, params.getNoticeId())
                .eq(WmsOutNoticeDetailEntity::getMatCode, params.getMatCode()) // 出库通知单中 只有一个物料编码  所以只有这两个条件
        );
        if (CollUtil.isEmpty(noticeDetails)) {
            throw new CheckedException("通知单明细查找失败！");
        }
        WmsOutNoticeDetailEntity noticeDetail = noticeDetails.get(0);

        // 查找出库单； 如果传来了出库单号，就使用传来的出库单号查，否则使用通知单号查
        List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(StrUtil.isBlank(params.getBillNo()), WmsOutBillEntity::getNoticeId, params.getNoticeId())
                .eq(StrUtil.isNotBlank(params.getBillNo()), WmsOutBillEntity::getBillNo, params.getBillNo())
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .eq(WmsOutBillEntity::getSource, InOutBillSourceEnum.APP.getCode())
                .orderByDesc(BaseCore::getCreateTime)
        );
        WmsOutBillEntity outBill;
        Double noticeNotOutNum;
        if (CollUtil.isEmpty(outBills)) {
            // 新增出库单
            outBill = new WmsOutBillEntity();
            BeanUtil.copyProperties(notice, outBill, "id");
            outBill.setNoticeId(params.getNoticeId());
            // 添加出库单明细
            WmsOutBillDetailEntity outBillDetailAdd = new WmsOutBillDetailEntity();
            BeanUtil.copyProperties(noticeDetail, outBillDetailAdd, "id");
            BeanUtil.copyProperties(params, outBillDetailAdd, CopyOptions.create().ignoreNullValue());
            outBillDetailAdd.setNoticeDetailId(noticeDetail.getId());
            // 操作数量
            outBillDetailAdd.setOperateUnit(params.getOperateUnit() == null ? outBillDetailAdd.getMatUnit() : params.getOperateUnit());
            outBillDetailAdd.setOperateNum(params.getOperateNum() == null ? outBillDetailAdd.getMatNum() : params.getOperateNum());

            outBill.setDetails(Collections.singletonList(outBillDetailAdd));
            noticeNotOutNum = saveDeepApp(outBill);
        } else {
            // 修改出库单数量
            outBill = outBills.get(0);
            // 查找出库单明细，如果有则修改数量，没有则新增明细
            // 由 物料编码、供应商编码、批次、容器码、库位 确定出库的物料，所以出库单明细存了这几个字段，按这几个字段来查
            List<WmsOutBillDetailEntity> billDetails = wmsOutBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                    .eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
                    .eq(WmsOutBillDetailEntity::getNoticeDetailId, noticeDetail.getId())
                    .eq(WmsOutBillDetailEntity::getMatCode, params.getMatCode())
                    .eq(WmsOutBillDetailEntity::getMatBatch, params.getMatBatch())
                    .eq(WmsOutBillDetailEntity::getSiteCode, params.getSiteCode())
                    .eq(StrUtil.isNotBlank(params.getContainerCode()), WmsOutBillDetailEntity::getContainerCode, params.getContainerCode())
                    .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsOutBillDetailEntity::getSupplierCode, params.getSupplierCode())
            );
            if (CollUtil.isEmpty(billDetails)) {
                // 新增明细
                WmsOutBillDetailEntity detailAdd = new WmsOutBillDetailEntity();
                // 实时库存的内容复制到出库单明细
                BeanUtil.copyProperties(noticeDetail, detailAdd, "id");
                BeanUtil.copyProperties(params, detailAdd, CopyOptions.create().ignoreNullValue());
                detailAdd.setNoticeDetailId(noticeDetail.getId());
                detailAdd.setBillId(outBill.getId());
                // 操作数量
                detailAdd.setOperateUnit(params.getOperateUnit() == null ? detailAdd.getMatUnit() : params.getOperateUnit());
                detailAdd.setOperateNum(params.getOperateNum() == null ? detailAdd.getMatNum() : params.getOperateNum());
                wmsOutBillDetailMapper.insert(detailAdd);
                // 出库单未完成条数+1
                wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
                        .set(WmsOutBillEntity::getDetailNotFinishNum, outBill.getDetailNotFinishNum() + 1)
                        .eq(BaseEntity::getId, outBill.getId())
                );
                // 修改通知单数量 当作新增了一个出库单
                outBill.setDetails(Collections.singletonList(detailAdd));
                noticeNotOutNum = createBillHandleNoticeOutNum(outBill);
            } else {
                // 修改明细数量
                WmsOutBillDetailEntity detailExist = billDetails.get(0);
                detailExist.setMatNum(detailExist.getMatNum() + params.getMatNum());
                // 操作数量
                detailExist.setOperateNum(Optional.ofNullable(detailExist.getOperateNum()).orElse(0d) + Optional.ofNullable(params.getOperateNum()).orElse(params.getMatNum()));
                wmsOutBillDetailMapper.updateById(detailExist);
                // 修改通知单数量 当作新增了一个出库单
                detailExist.setMatNum(params.getMatNum());
                outBill.setDetails(Collections.singletonList(detailExist));
                noticeNotOutNum = createBillHandleNoticeOutNum(outBill);
            }
        }
        // 将物料从库存中削减
        List<WmsLiveStockEntity> stockOuted = wmsLiveStockService.reduceStockWhenOut(outBill.getDetails().get(0));
        // 将削减的物料放到拣货区
        wmsPickingAreaService.insertByStockList(stockOuted, outBill.getBillNo());
        if (noticeNotOutNum <= 0) {
            // 通知单所有物料处理完了，出库单可以完成了，拣货区的物料可以删掉了
            wmsOutBillMapper.update(new LambdaUpdateWrapper<WmsOutBillEntity>()
                    .set(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                    .eq(BaseEntity::getId, outBill.getId())
            );
            // 删除拣货区物料 记录出库流水
            wmsPickingAreaService.deleteByOutBill(outBill);
            // 如果出库单都完成了，出库通知单也该完成了
            passBillHandleNoticeState(outBill);

            // 对documentId的单据进行操作
            outBill = wmsOutBillMapper.selectById(outBill.getId());
            List<WmsOutBillDetailEntity> detailEntities = wmsOutBillDetailMapper.selectList(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                    .eq(WmsOutBillDetailEntity::getBillId, outBill.getId())
            );
            outBill.setDetails(detailEntities);
            handleDocumentBizWhenAdd(outBill);
        }

        return outBill.getBillNo();
    }

    /**
     * app新增
     *
     * @return 通知单未入库总数
     */
    @Transactional(rollbackFor = Exception.class)
    public Double saveDeepApp(WmsOutBillEntity entity) {
        // 1、保存前的校验和处理
        beforeSaveDeep(entity);

        entity.setSource(InOutBillSourceEnum.APP.getCode()); // 来源app
        entity.setState(InOutBillStateEnum.APP_RUNNING.getCode()); // 状态处理中

        // 2、执行保存
        Double noticeNotOutNum = doSaveDeep(entity);

        return noticeNotOutNum;
    }

    @Override
    public List<WmsOutBillEntity> outBillListByDuId(Long id) {
        return wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getDocumentId, id));
    }

    @Override
    public Boolean outBillRemoveByDuId(Long id) {
        //删除主表
        List<WmsOutBillEntity> wmsOutBillEntities = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getDocumentId, id)
                .eq(WmsOutBillEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
                .and(wq -> wq.eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())
                        .or().eq(WmsOutBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())));
        List<Long> idList = wmsOutBillEntities.stream().map(BaseEntity::getId).toList();
        //删除子表
        if (CollUtil.isNotEmpty(idList)) {
            wmsOutBillDetailMapper.delete(new LambdaQueryWrapper<WmsOutBillDetailEntity>()
                    .in(WmsOutBillDetailEntity::getBillId, idList));
        }
        wmsOutBillMapper.deleteByIds(idList);
        return Boolean.TRUE;
    }

    /**
     * 查询主表列表
     *
     * @param wrapper 条件
     */
    @Override
    public List<WmsOutBillExcelVO> list(QueryWrapper<WmsOutBillEntity> wrapper) {
        List<WmsOutBillEntity> list = wmsOutBillMapper.selectList(wrapper);
        List<WmsOutBillExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutBillExcelVO vo = new WmsOutBillExcelVO();
            WmsOutBillEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setState(InOutBillStateEnum.getByCode(entity.getState()).getDesc());
            result.add(vo);
        }
        return result;
    }

    /**
     * 查询明细列表
     *
     * @param params 查询参数
     * @param ids    选择id
     */
    @Override
    public List<WmsOutBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
        List<WmsOutBillDetailEntity> list = wmsOutBillDetailMapper.fetchDetails(params, ids);
        List<WmsOutBillDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutBillDetailExcelVO vo = new WmsOutBillDetailExcelVO();
            WmsOutBillDetailEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setOperateNum(entity.getOperateNum() + entity.getOperateUnit());
            vo.setMatNum(entity.getMatNum() + entity.getMatUnit());
            vo.setState(InOutBillStateEnum.getByCode(entity.getState()).getDesc());
            result.add(vo);
        }
        return result;
    }
}
