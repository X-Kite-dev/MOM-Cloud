package com.kitex.mom.wms.service.inBill;

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
import com.kitex.mom.mdm.entity.MdmWarehouseLocationEntity;
import com.kitex.mom.mdm.feign.RemoteWarehouseLocationService;
import com.kitex.mom.wms.dto.WmsInBillDetailQueryDTO;
import com.kitex.mom.wms.dto.app.WmsInBillDetailAppDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.*;
import com.kitex.mom.wms.service.WmsDeliverGoodsReturnService;
import com.kitex.mom.wms.vo.WmsInBillDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInBillExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 入库单处理器抽象实现
 * 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的入库单处理
 *
 * @author kitex
 * @since 2024-12-16 14:28:29
 */

public abstract class AbstractWmsInBillHandler implements WmsInBillHandler<WmsInBillEntity, WmsInBillDetailEntity> {

    @Autowired
    protected WmsInNoticeMapper wmsInNoticeMapper;
    @Autowired
    protected WmsInNoticeDetailMapper wmsInNoticeDetailMapper;
    @Autowired
    private WmsInBillMapper wmsInBillMapper;
    @Autowired
    private WmsInBillDetailMapper wmsInBillDetailMapper;
    @Autowired
    private WmsLiveStockMapper wmsLiveStockMapper;
    @Autowired
    private WmsInoutFlowMapper wmsInoutFlowMapper;

    @Autowired
    private WmsContainerBarcodeMapper wmsContainerBarcodeMapper;
    @Autowired
    private WmsContainerUseHistoryMapper wmsContainerUseHistoryMapper;

    @Autowired
    private WmsDeliverGoodsReturnService wmsDeliverGoodsReturnService;

    @Autowired
    private RemoteSeqConfigService remoteSeqConfigService;

    @Autowired
    private RemoteWarehouseLocationService remoteWarehouseLocationService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsInBillEntity entity) {
        // 1、保存前的校验和处理
        beforeSaveDeep(entity);

        // 2、执行保存
        doSaveDeep(entity);

        return Boolean.TRUE;
    }

    /**
     * 保存前的处理
     *
     * @param entity 入库单
     */
    public void beforeSaveDeep(WmsInBillEntity entity) {
        // 1. 校验数据完整性
        checkData(entity);

        // 2. 校验仓库是否支持该业务
        checkWarehouse(entity.getWarehouseCode(), entity.getBillType());

        // 3. 设置默认状态
        setDefaultState(entity);

        // 4. 设置初始数据
        if (StrUtil.isBlank(entity.getBillNo())) {
            R<String> wms_in_bill_R = remoteSeqConfigService.create("wms_in_bill");
            if (!wms_in_bill_R.isOk()) {
                throw new CheckedException("生成入库单号失败！" + wms_in_bill_R.getMsg());
            }
            String billNo = wms_in_bill_R.getData();
            entity.setBillNo(billNo);
        }

        entity.setDetailFinishNum(0d);
        entity.setDetailNotFinishNum(Double.parseDouble(String.valueOf(entity.getDetails().size())));
    }

    /**
     * 执行保存单据
     *
     * @param entity 入库单
     * @return 通知单未入库总数
     */
    @Transactional
    public Double doSaveDeep(WmsInBillEntity entity) {
        // 1、 保存主表数据
        int result = wmsInBillMapper.insert(entity);
        if (result <= 0) {
            throw new CheckedException("保存入库单失败");
        }

        // 2、 保存明细数据
        List<WmsInBillDetailEntity> detailList = entity.getDetails();
        if (CollUtil.isNotEmpty(detailList)) {
            List<WmsInBillDetailEntity> list = detailList.stream().peek(detail ->
                    detail.setBillId(entity.getId())
                            .setFactoryCode(entity.getFactoryCode())
                            .setWarehouseCode(entity.getWarehouseCode())
                            .setWarehouseName(entity.getWarehouseName())
            ).toList();
            wmsInBillDetailMapper.insert(list);
        }

        // 3、更新通知单数量
        return createBillHandleNoticeInNum(entity);
    }

    /**
     * 新增入库单并审核通过
     *
     * @param inBill 入库单信息（包含明细）
     * @return true-成功，false-失败
     */
    @Override
    @Transactional
    public Boolean addAndPass(WmsInBillEntity inBill) {
        // 1、保存前的处理
        beforeSaveDeep(inBill);
        // 2、设置状态、明细数、入库时间
        LocalDateTime now = LocalDateTime.now();
        List<WmsInBillDetailEntity> details = inBill.getDetails();
        for (WmsInBillDetailEntity detail : details) {
            detail.setInTime(now);
        }
        inBill.setState(InOutBillStateEnum.PASSED.getCode());
        inBill.setDetailFinishNum(Double.parseDouble(String.valueOf(details.size())));
        inBill.setDetailNotFinishNum(0d);
        inBill.setInTime(now);
        inBill.setReviewer(SecurityUtils.getUser().getUsername());
        inBill.setReviewTime(now);
        // 3、执行保存
        doSaveDeep(inBill);
        // 4、判断通知单物料是否已全部完成入库
        passBillHandleNoticeState(inBill);
        // 5、增加实时库存、生成入库流水记录
        afterInStock(inBill);

        // 6、对documentId的单据进行操作
        handleDocumentBizWhenAdd(inBill);

        try {
            //如果来源于退货，更新退货单据数据
            if (DocumentEnum.D_RETURN.getCode().equals(inBill.getDocumentTable())) {
                wmsDeliverGoodsReturnService.updateReturnByBill(inBill);
            }
        } catch (Exception e) {
            throw new RuntimeException("更新退货信息失败！");
        }

        return Boolean.TRUE;
    }

    @Override
    public void setDefaultState(WmsInBillEntity entity) {
        // 默认状态为待提交
        entity.setState(InOutBillStateEnum.PENDING_COMMIT.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(WmsInBillEntity entity) {
        // 1. 校验数据完整性
        checkData(entity);

        // 2. 校验当前状态是否允许修改
        WmsInBillEntity existingNotice = wmsInBillMapper.selectById(entity.getId());
        Assert.notNull(existingNotice, "入库单不存在");

        NoticeStateEnum currentState = NoticeStateEnum.getByCode(existingNotice.getState());
        Assert.notNull(currentState, "入库单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");

        // 3. 更新主表数据
        int result = wmsInBillMapper.updateById(entity);
        if (result <= 0) {
            throw new CheckedException("更新入库单失败");
        }

        // 4. 更新明细数据
        if (CollUtil.isNotEmpty(entity.getDetails())) {
            updateDetails(entity.getId(), entity.getDetails());
        }

        return Boolean.TRUE;
    }

    @Override
    public Page<WmsInBillEntity> page(Page page, QueryWrapper<WmsInBillEntity> wrapper) {
        return wmsInBillMapper.selectPage(page, wrapper);
    }

    @Override
    public Map<String, Long> getCount(QueryWrapper<WmsInBillEntity> wrapper) {
        return wmsInBillMapper.selectList(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsInBillEntity::getState, Collectors.counting()));
    }


    @Override
    public Page<WmsInBillDetailEntity> pageDetails(Page page, WmsInBillDetailQueryDTO params) {
        return wmsInBillDetailMapper.fetchDetails(page, params, null);
    }

    @Override
    public WmsInBillEntity selectById(Long id) {
        // 1. 查询主表数据
        WmsInBillEntity notice = wmsInBillMapper.selectById(id);
        if (notice == null) {
            return null;
        }

        // 2. 查询明细数据
        List<WmsInBillDetailEntity> details = wmsInBillDetailMapper.selectList(
                Wrappers.<WmsInBillDetailEntity>lambdaQuery()
                        .eq(WmsInBillDetailEntity::getBillId, id)
        );
        notice.setDetails(details);

        return notice;
    }

    @Override
    public void checkData(WmsInBillEntity entity) {
        // 1. 校验必填数据
        if (CollUtil.isEmpty(entity.getDetails())) {
            throw new CheckedException("明细数据不能为空");
        }
        Optional.ofNullable(entity.getWarehouseCode()).orElseThrow(() -> new CheckedException("仓库编码不能为空"));
        Optional.ofNullable(entity.getBillType()).orElseThrow(() -> new CheckedException("单据类型不能为空"));

        // 2. 校验单据类型是否有效
        Assert.isTrue(InBillTypeEnum.isValid(entity.getBillType()) || OtherInBillTypeEnum.isValid(entity.getBillType()), "无效的单据类型：" + entity.getBillType());

        // 3. 校验明细是否重复
        Map<String, List<WmsInBillDetailEntity>> collectGroup = entity.getDetails().stream().collect(Collectors.groupingBy(it -> it.getMatCode() + it.getSiteCode() + it.getContainerCode() + it.getMatBatch() + it.getSupplierCode()));
        for (List<WmsInBillDetailEntity> value : collectGroup.values()) {
            if (value.size() > 1) {
                throw new CheckedException("数据重复【物料编码：" + value.get(0).getMatCode()
                        + "，库位：" + value.get(0).getSiteName()
                        + "，容器：" + Optional.ofNullable(value.get(0).getContainerCode()).orElse("空")
                        + "，批次：" + value.get(0).getMatBatch()
                        + "，供应商：" + value.get(0).getSupplierName()
                        + "】");
            }
        }
    }

    @Override
    public Boolean updateState(Long id, String state) {
        Assert.notNull(id, "通知单ID不能为空");

        // 校验状态是否有效
        NoticeStateEnum targetState = NoticeStateEnum.getByCode(state);
        Assert.notNull(targetState, "无效的目标状态：" + state);

        int result = wmsInBillMapper.update(null,
                Wrappers.<WmsInBillEntity>lambdaUpdate()
                        .eq(WmsInBillEntity::getId, id)
                        .set(WmsInBillEntity::getState, state));

        return result > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDetails(Long id, List<WmsInBillDetailEntity> details) {
        // 1. 校验通知单状态
        WmsInBillEntity notice = wmsInBillMapper.selectById(id);
        Assert.notNull(notice, "入库单不存在");

        InOutBillStateEnum currentState = InOutBillStateEnum.getByCode(notice.getState());
        Assert.notNull(currentState, "入库单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改明细");

        // 2. 删除原有明细
        wmsInBillDetailMapper.delete(
                Wrappers.<WmsInBillDetailEntity>lambdaQuery()
                        .eq(WmsInBillDetailEntity::getBillId, id)
        );

        // 3. 插入新明细
        if (CollUtil.isNotEmpty(details)) {
            List<WmsInBillDetailEntity> list = details.stream()
                    .peek(item -> item.setBillId(id).setId(null))
                    .toList();
            wmsInBillDetailMapper.insert(list);
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

        // 1. 校验入库单状态
        List<WmsInBillEntity> entities = wmsInBillMapper.selectBatchIds(Arrays.asList(ids));
        for (WmsInBillEntity entity : entities) {
            InOutBillStateEnum state = InOutBillStateEnum.getByCode(entity.getState());
            Assert.notNull(state, "入库单状态无效");
            Assert.isTrue(state.isEditable(),
                    String.format("入库单[%s]当前状态[%s]不允许删除", entity.getBillNo(), state.getDesc()));
        }

        // 2. 回退通知单的数量
        for (WmsInBillEntity entity : entities) {
            List<WmsInBillDetailEntity> details = wmsInBillDetailMapper.selectList(Wrappers.<WmsInBillDetailEntity>lambdaQuery()
                    .eq(WmsInBillDetailEntity::getBillId, entity.getId()));
            entity.setDetails(details);
            removeBillHandleNoticeInNum(entity);
        }

        // 3. 删除主表数据
        wmsInBillMapper.deleteByIds(Arrays.asList(ids));

        // 4. 删除明细数据
        wmsInBillDetailMapper.delete(
                Wrappers.<WmsInBillDetailEntity>lambdaQuery()
                        .in(WmsInBillDetailEntity::getBillId, ids)
        );

        return Boolean.TRUE;
    }

    /**
     * 提交入库单
     *
     * @param id 入库单id
     * @return true-成功，false-失败
     */
    @Override
    public Boolean commitBill(Long id) {
        // 1. 校验入库单状态
        WmsInBillEntity bill = wmsInBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "入库单状态无效");
        Assert.isTrue(state.isEditable(), String.format("入库单[%s]当前状态[%s]不允许提交", bill.getBillNo(), state.getDesc()));
        // 2. 更新状态为待审核
        wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
                .set(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_REVIEW.getCode())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * 审核通过入库单
     *
     * @param id 入库单id
     * @return true-成功，false-失败
     */
    @Override
    @Transactional
    public Boolean passBill(Long id) {
        // 1. 校验入库单状态
        WmsInBillEntity bill = wmsInBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "入库单状态无效");
        Assert.isTrue(state.isReviewable(), String.format("入库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));

        // 2. 更新状态为已通过
        List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper.selectList(
                Wrappers.<WmsInBillDetailEntity>lambdaQuery()
                        .eq(WmsInBillDetailEntity::getBillId, id)
        );
        bill.setDetails(billDetails);
        // 修改入库单明细数、入库时间和状态
        LocalDateTime now = LocalDateTime.now();
        wmsInBillMapper.update(Wrappers.<WmsInBillEntity>lambdaUpdate()
                .set(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .set(WmsInBillEntity::getDetailFinishNum, billDetails.size())
                .set(WmsInBillEntity::getDetailNotFinishNum, 0.0)
                .set(WmsInBillEntity::getInTime, now)
                .set(WmsInBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
                .set(WmsInBillEntity::getReviewTime, now)
                .eq(BaseEntity::getId, id)
        );
        wmsInBillDetailMapper.update(Wrappers.<WmsInBillDetailEntity>lambdaUpdate()
                .set(WmsInBillDetailEntity::getInTime, now)
                .in(BaseEntity::getId, billDetails.stream().map(BaseEntity::getId).collect(Collectors.toList()))
        );

        // 3. 更新通知单状态   判断通知单物料是否已全部完成入库
        passBillHandleNoticeState(bill);

        // 4、增加实时库存、生成入库流水记录
        for (WmsInBillDetailEntity detail : billDetails) {
            detail.setInTime(now);
        }
        afterInStock(bill);

        // 5、对documentId的单据进行操作
        handleDocumentBizWhenAdd(bill);

        try {
            //如果来源于退货，更新退货单据数据
            if (DocumentEnum.D_RETURN.getCode().equals(bill.getDocumentTable())) {
                wmsDeliverGoodsReturnService.updateReturnByBill(bill);
            }
        } catch (Exception e) {
            throw new RuntimeException("更新退货信息失败！");
        }

        return Boolean.TRUE;
    }

    /**
     * 创建入库单时修改通知单的入库数
     *
     * @param bill 入库单
     * @return 通知单未入库总数
     */
    @Transactional
    public Double createBillHandleNoticeInNum(WmsInBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return 0d;
        }
        List<WmsInBillDetailEntity> billDetails = bill.getDetails();
        // 判断是否超出通知入库总数
        WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("入库通知单查询失败"));
        List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper.selectList(
                Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
                        .eq(WmsInNoticeDetailEntity::getNoticeId, bill.getNoticeId())
        );
        Map<Long, WmsInNoticeDetailEntity> map = noticeDetails.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        Double totalNum = 0.0;
        for (WmsInBillDetailEntity billDetail : billDetails) {
            WmsInNoticeDetailEntity noticeDetail = Optional.ofNullable(map.get(billDetail.getNoticeDetailId())).orElseThrow(() -> new CheckedException("入库通知单明细[" + billDetail.getNoticeDetailId() + "]查找失败！物料编码：[" + billDetail.getMatCode() + "]"));
            Double billNum = Optional.ofNullable(billDetail.getMatNum()).orElseThrow(() -> new CheckedException("入库单明细标准单位数量为空"));
            Double noticeNum = Optional.ofNullable(noticeDetail.getNotInNum()).orElseThrow(() -> new CheckedException("入库通知单明细通知未入库量为空"));
            if (billNum > noticeNum) {
                throw new CheckedException(String.format("物料编码为[%s]的物料超出通知未入库量！", billDetail.getMatCode()));
            }
            // 修改通知单明细的入库数
            wmsInNoticeDetailMapper.update(Wrappers.<WmsInNoticeDetailEntity>lambdaUpdate()
                    .set(WmsInNoticeDetailEntity::getNotInNum, noticeDetail.getNotInNum() - billNum)
                    .set(WmsInNoticeDetailEntity::getInNum, noticeDetail.getInNum() + billNum)
                    .eq(BaseEntity::getId, noticeDetail.getId())
            );
            noticeDetail.setNotInNum(noticeDetail.getNotInNum() - billNum);
            noticeDetail.setInNum(noticeDetail.getInNum() + billNum);
            // 累计入库总数
            totalNum += billNum;
        }
        // 修改通知单总数量
        wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
                .set(WmsInNoticeEntity::getAllNotInNum, notice.getAllNotInNum() - totalNum)
                .set(WmsInNoticeEntity::getAllInNum, notice.getAllInNum() + totalNum)
                .set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())
                .eq(BaseEntity::getId, notice.getId())
        );
        return notice.getAllNotInNum() - totalNum;
    }

    /**
     * 删除入库单时修改通知单的入库数
     *
     * @param bill 入库单
     */
    @Transactional
    public void removeBillHandleNoticeInNum(WmsInBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return;
        }
        List<WmsInBillDetailEntity> billDetails = bill.getDetails();
        // 查找通知单
        WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("入库通知单查询失败"));
        List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper.selectList(
                Wrappers.<WmsInNoticeDetailEntity>lambdaQuery()
                        .eq(WmsInNoticeDetailEntity::getNoticeId, bill.getNoticeId())
        );
        Map<Long, WmsInNoticeDetailEntity> map = noticeDetails.stream().collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        double totalNum = 0d;
        for (WmsInBillDetailEntity billDetail : billDetails) {
            WmsInNoticeDetailEntity noticeDetail = map.get(billDetail.getNoticeDetailId());
            if (noticeDetail == null) {
                continue;
            }
            Double billNum = Optional.ofNullable(billDetail.getMatNum()).orElse(0d);
            Double notInNum = Optional.ofNullable(noticeDetail.getNotInNum()).orElse(0d);
            Double inNum = Optional.ofNullable(noticeDetail.getInNum()).orElse(0d);
            // 修改通知单明细的入库数
            wmsInNoticeDetailMapper.update(Wrappers.<WmsInNoticeDetailEntity>lambdaUpdate()
                    .set(WmsInNoticeDetailEntity::getNotInNum, notInNum + billNum)
                    .set(WmsInNoticeDetailEntity::getInNum, inNum - billNum)
                    .eq(BaseEntity::getId, noticeDetail.getId())
            );
            // 累计入库总数
            totalNum += billNum;
        }
        // 修改通知单总数量
        wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
                .set(WmsInNoticeEntity::getAllNotInNum, notice.getAllNotInNum() + totalNum)
                .set(WmsInNoticeEntity::getAllInNum, notice.getAllInNum() - totalNum)
                // 如果入库数变成0了  状态改为待处理，>0是处理中
                .set(WmsInNoticeEntity::getState, notice.getAllInNum() - totalNum <= 0 ? InOutNoticeStateEnum.READY.getCode() : InOutNoticeStateEnum.RUNNING.getCode())
                .eq(BaseEntity::getId, notice.getId())
        );
    }

    /**
     * 入库时修改通知单的状态
     * 当通知单内的物料全部入库之后设为已完成（入库单全部完成并且通知单未入库数量为0）
     *
     * @param bill 入库单
     */
    @Transactional
    public void passBillHandleNoticeState(WmsInBillEntity bill) {
        if (bill.getNoticeId() == null) {
            // 没有通知单的时候直接返回
            return;
        }
        // 查找还有没有未完成的入库单
        List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, bill.getNoticeId())
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode()) // pc不等于已通过
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode()) // app不等于已完成
        );
        if (inBills.size() == 0) {
            // 判断通知单未入库数是否为0
            WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(bill.getNoticeId())).orElseThrow(() -> new CheckedException("入库通知单查询失败"));
            if (notice.getAllNotInNum() <= 0) {
                // 修改通知单状态
                wmsInNoticeMapper.update(Wrappers.<WmsInNoticeEntity>lambdaUpdate()
                        .set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.FINISHED.getCode())
                        .set(WmsInNoticeEntity::getCompleteTime, LocalDateTime.now())
                        .eq(BaseEntity::getId, notice.getId())
                );
            }
        }
    }

    /**
     * 入库通过之后
     * 增加实时库存，生成入库流水
     */
    @Transactional
    public void afterInStock(WmsInBillEntity inBill) {
        List<WmsInBillDetailEntity> details = inBill.getDetails();
        for (WmsInBillDetailEntity detail : details) {
            // 实时库存
            WmsLiveStockEntity liveStock = new WmsLiveStockEntity();
            BeanUtil.copyProperties(detail, liveStock, "id");
            liveStock.setStatus(0);
            wmsLiveStockMapper.insert(liveStock);
            // 入库流水
            WmsInoutFlowEntity inoutFlow = new WmsInoutFlowEntity();
            BeanUtil.copyProperties(detail, inoutFlow, "id");
            inoutFlow.setBillNo(inBill.getBillNo());
            inoutFlow.setBillType(inBill.getBillType());
            inoutFlow.setOperateType(InOutEnum.IN.getCode());
            wmsInoutFlowMapper.insert(inoutFlow);
            // 容器占用
            if (StrUtil.isNotBlank(detail.getContainerCode())) {
                List<WmsContainerBarcodeEntity> containerList = wmsContainerBarcodeMapper.selectList(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
                        .eq(WmsContainerBarcodeEntity::getCode, detail.getContainerCode())
                );
                if (containerList.size() > 0) {
                    WmsContainerBarcodeEntity cont = containerList.get(0);
                    // 修改在用标志
                    wmsContainerBarcodeMapper.update(new LambdaUpdateWrapper<WmsContainerBarcodeEntity>()
                            .set(WmsContainerBarcodeEntity::getIsUsed, YesNoEnum.YES.getCode())
                            .eq(WmsContainerBarcodeEntity::getId, cont.getId())
                    );
                    // 新增使用历史
                    Long count = wmsContainerUseHistoryMapper.selectCount(new LambdaQueryWrapper<WmsContainerUseHistoryEntity>()
                            .eq(WmsContainerUseHistoryEntity::getCode, cont.getCode())
                            .eq(WmsContainerUseHistoryEntity::getMatCode, detail.getMatCode())
                            .isNull(WmsContainerUseHistoryEntity::getEndTime)
                    );
                    // 如果不存在未结束的使用历史，则新增
                    if (count <= 0) {
                        WmsContainerUseHistoryEntity history = new WmsContainerUseHistoryEntity()
                                .setCode(cont.getCode())
                                .setSiteCode(detail.getSiteCode())
                                .setSiteName(detail.getSiteName())
                                .setStartTime(detail.getInTime())
                                .setMatCode(detail.getMatCode())
                                .setMatName(detail.getMatName())
                                .setFactoryCode(detail.getFactoryCode());
                        wmsContainerUseHistoryMapper.insert(history);
                    }
                }
            }
        }

    }

    /**
     * 审核驳回入库单
     *
     * @param params 入库单id、原因
     * @return true-成功，false-失败
     */
    @Override
    public Boolean rejectBill(WmsInBillEntity params) {
        Long id = Optional.ofNullable(params.getId()).orElseThrow(() -> new CheckedException("入库单id为空"));
        // 1. 校验入库单状态
        WmsInBillEntity bill = wmsInBillMapper.selectById(id);
        InOutBillStateEnum state = InOutBillStateEnum.getByCode(bill.getState());
        Assert.notNull(state, "入库单状态无效");
        Assert.isTrue(state.isReviewable(), String.format("入库单[%s]当前状态[%s]不允许审核", bill.getBillNo(), state.getDesc()));
        // 2. 更新状态为待提交
        wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
                .set(WmsInBillEntity::getState, InOutBillStateEnum.PENDING_COMMIT.getCode())
                .set(WmsInBillEntity::getReviewer, SecurityUtils.getUser().getUsername())
                .set(WmsInBillEntity::getReviewTime, LocalDateTime.now())
                .set(WmsInBillEntity::getRejectReason, params.getRejectReason())
                .eq(BaseEntity::getId, id)
        );
        return Boolean.TRUE;
    }

    /**
     * 校验此库位是否属于此仓库
     *
     * @param warehouseCode 仓库编码
     * @param siteCode      库位编码
     * @return 结果
     */
    public boolean checkLocationOfWarehouse(String warehouseCode, String siteCode) {
        MdmWarehouseLocationEntity location = Optional.ofNullable(remoteWarehouseLocationService.getByCode(siteCode).getData()).orElse(new MdmWarehouseLocationEntity());
        return StrUtil.equals(warehouseCode, location.getWarehouseCode());
    }

    /**
     * 校验此容器是否属于此仓库
     *
     * @param warehouseCode 仓库编码
     * @param containerCode 容器编码
     * @return 结果
     */
    public boolean checkContainerOfWarehouse(String warehouseCode, String containerCode) {
        WmsContainerBarcodeEntity container = Optional.ofNullable(wmsContainerBarcodeMapper.selectOne(new LambdaQueryWrapper<WmsContainerBarcodeEntity>()
                .eq(WmsContainerBarcodeEntity::getCode, containerCode)
                .last("limit 1")
        )).orElse(new WmsContainerBarcodeEntity());
        return StrUtil.equals(warehouseCode, container.getWarehouseCode());
    }

    /**
     * 入库校验
     *
     * @param warehouseCode 仓库编码
     * @param params        入库参数
     */
    public void appInCheck(String warehouseCode, WmsInBillDetailAppDTO params) {
        // 校验库位是不是这个仓库的
        boolean checkLocationRes = checkLocationOfWarehouse(warehouseCode, params.getSiteCode());
        if (!checkLocationRes) {
            throw new CheckedException(String.format("库位【%s】不属于仓库【%s】", params.getSiteCode(), warehouseCode));
        }
        // 判断容器是不是这个仓库的
        if (StrUtil.isNotBlank(params.getContainerCode())) {
            boolean checkContainerRes = checkContainerOfWarehouse(warehouseCode, params.getContainerCode());
            if (!checkContainerRes) {
                throw new CheckedException(String.format("容器【%s】不属于仓库【%s】", params.getContainerCode(), warehouseCode));
            }
        }
    }

    /**
     * app入库
     * 提交某一条物料数量，更新入库单入库数量或生成入库单
     * <p>
     * 根据通知单号、物料编码、批次、供应商查找通知单明细；
     * 查找有没有入库单，查找入库单明细有没有这个物料；
     *
     * @param params 物料数量等
     * @return R  对象
     */
    @Override
    @Transactional
    public String setInBillNum(WmsInBillDetailAppDTO params) {
        // 查找通知单
        WmsInNoticeEntity notice = Optional.ofNullable(wmsInNoticeMapper.selectById(params.getNoticeId())).orElseThrow(() -> new CheckedException("通知单查找失败！"));
        if (StrUtil.equals(notice.getState(), InOutNoticeStateEnum.FINISHED.getCode()) || StrUtil.equals(notice.getState(), InOutNoticeStateEnum.CANCELED.getCode())) {
            throw new CheckedException(String.format("该通知单%s，无法进行入库操作！", InOutNoticeStateEnum.getByCode(notice.getState()).getName()));
        }
        // 入库校验
        appInCheck(notice.getWarehouseCode(), params);
        // 通过物料查询通知单明细
        List<WmsInNoticeDetailEntity> noticeDetails = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                .eq(WmsInNoticeDetailEntity::getNoticeId, params.getNoticeId())
                .eq(WmsInNoticeDetailEntity::getMatCode, params.getMatCode())
                .eq(WmsInNoticeDetailEntity::getMatBatch, params.getMatBatch())
                .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsInNoticeDetailEntity::getSupplierCode, params.getSupplierCode())
        );
        if (noticeDetails.size() == 0) {// 入库通知单的批次可以不填,没查到的话批次为空查一下
            noticeDetails = wmsInNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsInNoticeDetailEntity>()
                    .eq(WmsInNoticeDetailEntity::getNoticeId, params.getNoticeId())
                    .eq(WmsInNoticeDetailEntity::getMatCode, params.getMatCode())
                    .isNull(WmsInNoticeDetailEntity::getMatBatch) // 入库通知单的批次可以不填
                    .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsInNoticeDetailEntity::getSupplierCode, params.getSupplierCode())
            );
        }
        if (CollUtil.isEmpty(noticeDetails)) {
            throw new CheckedException("通知单明细查找失败！");
        }
        WmsInNoticeDetailEntity noticeDetail = noticeDetails.get(0);
        // 查找入库单； 如果传来了入库单号，就使用传来的入库单号查，否则使用通知单号查
        List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(StrUtil.isBlank(params.getBillNo()), WmsInBillEntity::getNoticeId, params.getNoticeId())
                .eq(StrUtil.isNotBlank(params.getBillNo()), WmsInBillEntity::getBillNo, params.getBillNo())
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .eq(WmsInBillEntity::getSource, InOutBillSourceEnum.APP.getCode())
                .orderByDesc(BaseCore::getCreateTime)
        );
        WmsInBillEntity inBill;
        Double noticeNotInNum;
        if (CollUtil.isEmpty(inBills)) {
            // 新增入库单
            inBill = new WmsInBillEntity();
            BeanUtil.copyProperties(notice, inBill, "id");
            inBill.setNoticeId(params.getNoticeId());
            // 添加入库单明细
            WmsInBillDetailEntity inBillDetailAdd = new WmsInBillDetailEntity();
            BeanUtil.copyProperties(noticeDetail, inBillDetailAdd, "id");
            BeanUtil.copyProperties(params, inBillDetailAdd, CopyOptions.create().ignoreNullValue());
            inBillDetailAdd.setNoticeDetailId(noticeDetail.getId());
            // 操作数量
            inBillDetailAdd.setOperateUnit(params.getOperateUnit() == null ? inBillDetailAdd.getMatUnit() : params.getOperateUnit());
            inBillDetailAdd.setOperateNum(params.getOperateNum() == null ? inBillDetailAdd.getMatNum() : params.getOperateNum());
            inBill.setDetails(Collections.singletonList(inBillDetailAdd));
            // 调用新增方法
            noticeNotInNum = saveDeepApp(inBill);
        } else {
            // 修改入库单数量
            inBill = inBills.get(0);
            // 查找入库单明细，如果有则修改数量，没有则新增明细
            List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                    .eq(WmsInBillDetailEntity::getBillId, inBill.getId())
                    .eq(WmsInBillDetailEntity::getNoticeDetailId, noticeDetail.getId())
                    .eq(WmsInBillDetailEntity::getMatCode, params.getMatCode())
                    .eq(WmsInBillDetailEntity::getMatBatch, params.getMatBatch())
                    .eq(WmsInBillDetailEntity::getSiteCode, params.getSiteCode())
                    .eq(StrUtil.isNotBlank(params.getSupplierCode()), WmsInBillDetailEntity::getSupplierCode, params.getSupplierCode())
            );
            if (CollUtil.isEmpty(billDetails)) {
                // 新增明细
                WmsInBillDetailEntity detailAdd = new WmsInBillDetailEntity();
                // 通知单明细的内容复制到入库单明细
                Double notInNum = Optional.ofNullable(noticeDetail.getNotInNum()).orElseThrow(() -> new CheckedException("入库通知单明细未入库数异常！"));
                if (notInNum <= 0) {
                    throw new CheckedException("该物料已全部入库！");
                }
                BeanUtil.copyProperties(noticeDetail, detailAdd, "id");
                BeanUtil.copyProperties(params, detailAdd, CopyOptions.create().ignoreNullValue());
                detailAdd.setNoticeDetailId(noticeDetail.getId());
                detailAdd.setBillId(inBill.getId());
                // 操作数量
                detailAdd.setOperateUnit(params.getOperateUnit() == null ? detailAdd.getMatUnit() : params.getOperateUnit());
                detailAdd.setOperateNum(params.getOperateNum() == null ? detailAdd.getMatNum() : params.getOperateNum());
                wmsInBillDetailMapper.insert(detailAdd);
                // 入库单未完成条数+1
                wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
                        .set(WmsInBillEntity::getDetailNotFinishNum, inBill.getDetailNotFinishNum() + 1)
                        .eq(BaseEntity::getId, inBill.getId())
                );
                // 修改通知单数量 当作新增了一个入库单
                inBill.setDetails(Collections.singletonList(detailAdd));
                noticeNotInNum = createBillHandleNoticeInNum(inBill);
            } else {
                // 修改明细数量
                WmsInBillDetailEntity detailExist = billDetails.get(0);
                detailExist.setMatNum(detailExist.getMatNum() + params.getMatNum());
                // 操作数量
                detailExist.setOperateNum(Optional.ofNullable(detailExist.getOperateNum()).orElse(0d) + Optional.ofNullable(params.getOperateNum()).orElse(0d));
                wmsInBillDetailMapper.updateById(detailExist);
                // 修改通知单数量 当作新增了一个入库单
                detailExist.setMatNum(params.getMatNum());
                inBill.setDetails(Collections.singletonList(detailExist));
                noticeNotInNum = createBillHandleNoticeInNum(inBill);
            }
        }
        if (noticeNotInNum <= 0) {
            // 通知单所有物料处理完了，入库单可以完成了
            wmsInBillMapper.update(new LambdaUpdateWrapper<WmsInBillEntity>()
                    .set(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                    .eq(BaseEntity::getId, inBill.getId())
            );
            // 如果入库单都完成了，入库通知单也该完成了
            passBillHandleNoticeState(inBill);
            // 增加实时库存、生成入库流水记录
            List<WmsInBillDetailEntity> billDetails = wmsInBillDetailMapper.selectList(new LambdaQueryWrapper<WmsInBillDetailEntity>()
                    .eq(WmsInBillDetailEntity::getBillId, inBill.getId())
            );
            LocalDateTime now = LocalDateTime.now();
            for (WmsInBillDetailEntity detail : billDetails) {
                detail.setInTime(now);
            }
            inBill.setDetails(billDetails);
            afterInStock(inBill);
        }

        // 对documentId的单据进行操作
        handleDocumentBizWhenAdd(inBill);

        return inBill.getBillNo();
    }

    /**
     * app新增
     *
     * @return 通知单未入库总数
     */
    @Transactional(rollbackFor = Exception.class)
    public Double saveDeepApp(WmsInBillEntity entity) {
        // 1、保存前的校验和处理
        beforeSaveDeep(entity);

        entity.setSource(InOutBillSourceEnum.APP.getCode()); // 来源app
        entity.setState(InOutBillStateEnum.APP_RUNNING.getCode()); // 状态处理中

        // 2、执行保存
        Double notInNum = doSaveDeep(entity);

        return notInNum;
    }

    /**
     * 查询主表列表
     *
     * @param wrapper 条件
     */
    @Override
    public List<WmsInBillExcelVO> list(QueryWrapper<WmsInBillEntity> wrapper) {
        List<WmsInBillEntity> list = wmsInBillMapper.selectList(wrapper);
        List<WmsInBillExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsInBillExcelVO vo = new WmsInBillExcelVO();
            WmsInBillEntity entity = list.get(i);
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
    public List<WmsInBillDetailExcelVO> listDetails(WmsInBillDetailQueryDTO params, Long[] ids) {
        List<WmsInBillDetailEntity> list = wmsInBillDetailMapper.fetchDetails(params, ids);
        List<WmsInBillDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsInBillDetailExcelVO vo = new WmsInBillDetailExcelVO();
            WmsInBillDetailEntity entity = list.get(i);
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
