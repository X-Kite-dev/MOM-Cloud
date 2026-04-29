package com.kitex.mom.wms.service.outNotice;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.kitex.common.core.entity.BaseCore;
import com.kitex.common.core.entity.BaseEntity;
import com.kitex.common.core.exception.CheckedException;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.*;
import com.kitex.mom.wms.enums.*;
import com.kitex.mom.wms.mapper.WmsLiveStockMapper;
import com.kitex.mom.wms.mapper.WmsOutBillMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsOutNoticeMapper;
import com.kitex.mom.wms.vo.WmsOutNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsOutNoticeExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 出库通知单处理器抽象实现 提供通用的业务逻辑实现，子类可以通过继承该类来实现特定类型的出库通知单处理
 *
 * @author Cascade
 * @since 2024-12-16
 */
public abstract class AbstractWmsOutNoticeHandler implements WmsOutNoticeHandler<WmsOutNoticeEntity, WmsOutNoticeDetailEntity> {

    @Autowired
    private WmsOutNoticeMapper wmsOutNoticeMapper;

    @Autowired
    private WmsOutNoticeDetailMapper wmsOutNoticeDetailMapper;

    @Autowired
    private WmsLiveStockMapper wmsLiveStockMapper;

    @Autowired
    private WmsOutBillMapper wmsOutBillMapper;

    @Autowired
    private RemoteMaterialService remoteMaterialService;

    @Override
    public List<WmsOutNoticeEntity> getNoticeList(List<Long> ids) {
        List<WmsOutNoticeEntity> wmsOutNoticeEntities = wmsOutNoticeMapper.selectByIds(ids);

        List<WmsOutNoticeDetailEntity> wmsOutNoticeDetailEntities = wmsOutNoticeDetailMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeDetailEntity>()
                .in(WmsOutNoticeDetailEntity::getNoticeId, ids));
        Map<Long, List<WmsOutNoticeDetailEntity>> detailsMap = wmsOutNoticeDetailEntities.stream().collect(Collectors.groupingBy(WmsOutNoticeDetailEntity::getNoticeId, Collectors.toList()));
        wmsOutNoticeEntities.forEach(item -> {
            List<WmsOutNoticeDetailEntity> detailList = detailsMap.get(item.getId());
            if (CollUtil.isNotEmpty(detailList)) {
                item.setDetailList(detailList);
            }
        });
        return wmsOutNoticeEntities;
    }

    ;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveDeep(WmsOutNoticeEntity wmsOutNotice) {
        // 1. 校验数据完整性
        checkData(wmsOutNotice);

        // 2. 校验仓库是否支持该业务
        checkWarehouse(wmsOutNotice.getWarehouseCode(), wmsOutNotice.getBillType());

        // 3. 设置默认状态 和 数量
        setDefaultState(wmsOutNotice);
        // 设置初始数量
        List<WmsOutNoticeDetailEntity> details = wmsOutNotice.getDetailList();
        Double sum = details.stream()
                .map(WmsOutNoticeDetailEntity::getNoticeOutNum)
                .filter(Objects::nonNull)
                .reduce(0.0, Double::sum);
        wmsOutNotice.setNoticeOutNum(sum);
        wmsOutNotice.setAllNotOutNum(sum);
        wmsOutNotice.setAllOutNum(0.0);

        // 4. 保存主表数据
        int result = wmsOutNoticeMapper.insert(wmsOutNotice);
        if (result <= 0) {
            throw new CheckedException("保存出库通知单失败");
        }

        // 5. 保存明细数据
        if (CollUtil.isNotEmpty(details)) {
            /* 处理明细信息start */
            for (WmsOutNoticeDetailEntity detail : details) {
                detail.setNoticeId(wmsOutNotice.getId());
                detail.setWorkshop(wmsOutNotice.getWorkshop());
                detail.setNotOutNum(detail.getNoticeOutNum());
                detail.setOutNum(0.0);
                detail.setWarehouseCode(wmsOutNotice.getWarehouseCode());
                detail.setWarehouseName(wmsOutNotice.getWarehouseName());
                detail.setFactoryCode(wmsOutNotice.getFactoryCode());
            }
            /* 处理明细信息end */
            // 新增明细
            wmsOutNoticeDetailMapper.insert(details);
        }

        return Boolean.TRUE;
    }

    @Override
    public void setDefaultState(WmsOutNoticeEntity wmsOutNotice) {
        // 默认状态为待审核
        wmsOutNotice.setState(InOutNoticeStateEnum.READY.getCode());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDeep(WmsOutNoticeEntity wmsOutNotice) {
        // 1. 校验数据完整性
        checkData(wmsOutNotice);

        // 2. 校验当前状态是否允许修改
        WmsOutNoticeEntity existingNotice = wmsOutNoticeMapper.selectById(wmsOutNotice.getId());
        Assert.notNull(existingNotice, "出库通知单不存在");

        NoticeStateEnum currentState = NoticeStateEnum.getByCode(existingNotice.getState());
        Assert.notNull(currentState, "出库通知单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改");

        // 3. 更新主表数据
        int result = wmsOutNoticeMapper.updateById(wmsOutNotice);
        if (result <= 0) {
            throw new CheckedException("更新出库通知单失败");
        }

        // 4. 更新明细数据
        if (CollUtil.isNotEmpty(wmsOutNotice.getDetailList())) {
            List<WmsOutNoticeDetailEntity> detailEntities = wmsOutNotice.getDetailList().stream().peek(item -> item.setFactoryCode(wmsOutNotice.getFactoryCode())).toList();
            wmsOutNotice.setDetailList(detailEntities);
            updateDetails(wmsOutNotice.getId(), wmsOutNotice.getDetailList());
        }

        return Boolean.TRUE;
    }

    @Override
    public Page<WmsOutNoticeEntity> page(Page page, QueryWrapper<WmsOutNoticeEntity> wrapper) {
        return wmsOutNoticeMapper.selectPage(page, wrapper);
    }

    @Override
    public Map<String, Long> getCount(QueryWrapper<WmsOutNoticeEntity> wrapper) {
        return wmsOutNoticeMapper.selectList(wrapper)
                .stream()
                .collect(Collectors.groupingBy(WmsOutNoticeEntity::getState, Collectors.counting()));
    }


    @Override
    public Page<WmsOutNoticeDetailEntity> pageDetails(Page page, WmsInNoticeDetailQueryDTO params) {
        return wmsOutNoticeDetailMapper.fetchDetails(page, params, null);
    }

    @Override
    public WmsOutNoticeEntity selectById(Long id) {
        // 1. 查询主表数据
        WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(id);
        if (notice == null) {
            return null;
        }

        // 2. 查询明细数据
        List<WmsOutNoticeDetailEntity> details = wmsOutNoticeDetailMapper
                .selectList(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, id));
        notice.setDetailList(details);

        return notice;
    }

    /**
     * 根据通知单id查询实时库存
     */
    @Override
    public WmsOutBillEntity getLiveStockByNoticeId(Long id) {
        WmsOutNoticeEntity notice = Optional.ofNullable(selectById(id)).orElseThrow(() -> new CheckedException("出库通知单查找失败，请确认单号！"));
        List<WmsOutNoticeDetailEntity> detailList = notice.getDetailList();
        List<WmsOutBillDetailEntity> billDetails = new ArrayList<>();
        for (WmsOutNoticeDetailEntity detail : detailList) {
            if (detail.getNotOutNum() == 0) {
                // 这个物料明细完成了就不要查了
                continue;
            }
            // 查询物料是否配置先入先出逻辑
            MdmMaterialEntity mdmMaterial = remoteMaterialService.getDetails(detail.getMatCode()).getData();
            // 决定排序字段
            String orderByField = Optional.ofNullable(mdmMaterial)
                    .map(MdmMaterialEntity::getFifo)
                    .filter(Boolean.TRUE::equals)
                    .map(fifo -> mdmMaterial.getFifoField())
                    .orElse("in_time");
            List<WmsLiveStockEntity> liveStocks;
            if ("in_time".equals(orderByField)) {
                // 如果是按入库时间先进先出，直接排序查出来
                liveStocks = wmsLiveStockMapper.selectList(new LambdaQueryWrapper<WmsLiveStockEntity>()
                        .eq(WmsLiveStockEntity::getWarehouseCode, detail.getWarehouseCode())
                        .eq(WmsLiveStockEntity::getMatCode, detail.getMatCode())
                        .eq(WmsLiveStockEntity::getStatus, YesNoEnum.NO.getCode())
                        .gt(WmsLiveStockEntity::getMatNum, 0)
                        .orderByAsc(WmsLiveStockEntity::getInTime)
                );
            } else {
                // 查找此物料的库存  由 物料编码、供应商编码、批次、容器码、库位 确定出库的物料；汇总之后再由先进先出字段进行排序推荐
                // todo 汇总了之后就拿不到质检结果了
                liveStocks = wmsLiveStockMapper.selectList(
                        new QueryWrapper<WmsLiveStockEntity>()
                                .select("mat_code", "mat_name", "mat_spec", "mat_batch", "supplier_code", "container_code", "site_code", "sum(mat_num) as mat_num")
                                .eq("warehouse_code", detail.getWarehouseCode())
                                .eq("mat_code", detail.getMatCode())
                                .eq("status", YesNoEnum.NO.getCode())
                                .gt("mat_num", 0)
                                .groupBy("mat_code", "mat_name", "mat_spec", "mat_batch", "supplier_code", "container_code", "site_code")
                                .orderByAsc(orderByField)
                );
            }
            // 出库数量
            double outNum = detail.getNotOutNum();
            // 进行推荐物料
            for (WmsLiveStockEntity liveStock : liveStocks) {
                WmsOutBillDetailEntity liveDetail = new WmsOutBillDetailEntity();
                BeanUtil.copyProperties(detail, liveDetail, "id");
                liveDetail.setSupplierCode(liveStock.getSupplierCode());
                liveDetail.setMatBatch(liveStock.getMatBatch());
                liveDetail.setMatSpec(liveStock.getMatSpec());
                liveDetail.setContainerCode(liveStock.getContainerCode());
                liveDetail.setSiteCode(liveStock.getSiteCode());
                // 当前库存数量
                liveDetail.setMaxOutNum(liveStock.getMatNum());
                liveDetail.setMatNum(outNum > liveStock.getMatNum() ? liveStock.getMatNum() : outNum);
                liveDetail.setOperateUnit(liveDetail.getMatUnit());
                liveDetail.setOperateNum(liveDetail.getMatNum());
                liveDetail.setNoticeDetailId(detail.getId());
                billDetails.add(liveDetail);
                // 物料够了就退出循环
                outNum -= liveStock.getMatNum();
                if (outNum <= 0) {
                    break;
                }
            }
            // todo 查询供应商名称
            // todo 查询库位名称
        }
        WmsOutBillEntity outBill = new WmsOutBillEntity();
        BeanUtil.copyProperties(notice, outBill, "id");
        outBill.setNoticeId(id);
        outBill.setDetails(billDetails);
        return outBill;
    }

    /**
     * 根据通知单id查询实时库存-移动端
     */
    @Override
    public WmsOutBillEntity getLiveStockByNoticeIdApp(Long id) {
        WmsOutBillEntity res = getLiveStockByNoticeId(id);
        List<WmsOutNoticeDetailEntity> details = wmsOutNoticeDetailMapper
                .selectList(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, id));
        res.setNoticeDetails(details);
        // 根据通知单号查询出库单号（app操作时需要用到）
        List<WmsOutBillEntity> outBills = wmsOutBillMapper.selectList(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getNoticeId, id)
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .orderByDesc(BaseCore::getCreateTime)
                .last("limit 1")
        );
        if (outBills.size() > 0) {
            res.setBillNo(outBills.get(0).getBillNo());
        }
        return res;
    }

    @Override
    public void checkData(WmsOutNoticeEntity wmsOutNotice) {
        // 1. 校验必填数据
        if (CollUtil.isEmpty(wmsOutNotice.getDetailList())) {
            throw new CheckedException("明细数据不能为空");
        }
        Optional.ofNullable(wmsOutNotice.getWarehouseCode()).orElseThrow(() -> new CheckedException("仓库编码不能为空"));
        Optional.ofNullable(wmsOutNotice.getBillType()).orElseThrow(() -> new CheckedException("单据类型不能为空"));

        // 2. 校验单据类型是否有效
        Assert.isTrue(OutBillTypeEnum.isValid(wmsOutNotice.getBillType()) || OtherOutBillTypeEnum.isValid(wmsOutNotice.getBillType()), "无效的单据类型：" + wmsOutNotice.getBillType());
    }

    @Override
    public Boolean updateState(Long id, String state) {
        Assert.notNull(id, "通知单ID不能为空");

        // 校验状态是否有效
        NoticeStateEnum targetState = NoticeStateEnum.getByCode(state);
        Assert.notNull(targetState, "无效的目标状态：" + state);

        int result = wmsOutNoticeMapper.update(null,
                Wrappers.<WmsOutNoticeEntity>lambdaUpdate()
                        .eq(WmsOutNoticeEntity::getId, id)
                        .set(WmsOutNoticeEntity::getState, state));

        return result > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateDetails(Long id, List<WmsOutNoticeDetailEntity> details) {
        // 1. 校验通知单状态
        WmsOutNoticeEntity notice = wmsOutNoticeMapper.selectById(id);
        Assert.notNull(notice, "出库通知单不存在");

        NoticeStateEnum currentState = NoticeStateEnum.getByCode(notice.getState());
        Assert.notNull(currentState, "出库通知单状态无效");
        Assert.isTrue(currentState.isEditable(), "当前状态不允许修改明细");

        // 2. 删除原有明细
        wmsOutNoticeDetailMapper
                .delete(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().eq(WmsOutNoticeDetailEntity::getNoticeId, id));

        // 3. 插入新明细
        if (CollUtil.isNotEmpty(details)) {
            List<WmsOutNoticeDetailEntity> list = details.stream().peek(item -> item.setNoticeId(id)).toList();
            wmsOutNoticeDetailMapper.insertOrUpdate(list);
        }

        return Boolean.TRUE;
    }

    @Override
    public Boolean checkWarehouse(String warehouseCode, String billType) {
//		Assert.notNull(warehouseCode, "仓库编码不能为空");
//		NoticeBillTypeEnum billTypeEnum = NoticeBillTypeEnum.getByCode(billType);
//		Assert.notNull(billTypeEnum, "无效的单据类型：" + billType);

        // TODO: 实现仓库业务类型校验逻辑
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(String[] ids) {
        if (CollUtil.isEmpty(Arrays.asList(ids))) {
            return Boolean.TRUE;
        }

        // 1. 校验所有通知单状态
        List<WmsOutNoticeEntity> notices = wmsOutNoticeMapper.selectBatchIds(Arrays.asList(ids));
        for (WmsOutNoticeEntity notice : notices) {
            NoticeStateEnum state = NoticeStateEnum.getByCode(notice.getState());
            Assert.notNull(state, "出库通知单状态无效");
            Assert.isTrue(state.isEditable(), String.format("通知单[%s]当前状态[%s]不允许删除", notice.getId(), state.getDesc()));
        }

        // 2. 删除主表数据
        wmsOutNoticeMapper.deleteByIds(Arrays.asList(ids));

        // 3. 删除明细数据
        wmsOutNoticeDetailMapper
                .delete(Wrappers.<WmsOutNoticeDetailEntity>lambdaQuery().in(WmsOutNoticeDetailEntity::getNoticeId, ids));

        return Boolean.TRUE;
    }

    /**
     * 作废单据
     *
     * @param id 通知单id
     */
    @Override
    public void voidObjs(Long id) {
        WmsOutNoticeEntity noticeEntity = Optional.ofNullable(wmsOutNoticeMapper.selectById(id))
                .orElseThrow(() -> new CheckedException("单据不存在！"));

        // 判断是否存在未完成的出库单
        Long count = wmsOutBillMapper.selectCount(new LambdaQueryWrapper<WmsOutBillEntity>()
                .eq(WmsOutBillEntity::getNoticeId, id)
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
        );
        if (count > 0) {
            throw new CheckedException("存在未完成的出库单，不可作废！");
        }

        wmsOutNoticeMapper.update(new LambdaUpdateWrapper<WmsOutNoticeEntity>()
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .eq(BaseEntity::getId, id));
    }

    @Override
    public List<WmsOutNoticeEntity> outNoticeListByDuId(Long id) {
        //根据业务单号查询通知单
        return wmsOutNoticeMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeEntity>()
                .eq(WmsOutNoticeEntity::getDocumentId, id));
    }

    @Override
    public Boolean outNoticeRemoveByDuId(Long id) {
        //作废 待处理 、处理中的出库通知单
        wmsOutNoticeMapper.update(new UpdateWrapper<WmsOutNoticeEntity>().lambda()
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .eq(WmsOutNoticeEntity::getDocumentId, id)
                .eq(WmsOutNoticeEntity::getDocumentTable, DocumentEnum.DELIVER.getCode())
                .and(wq -> wq.eq(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.READY.getCode())
                        .or().eq(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.RUNNING.getCode())));
        return Boolean.TRUE;
    }

    /**
     * 查询主表列表
     */
    @Override
    public List<WmsOutNoticeExcelVO> list(QueryWrapper<WmsOutNoticeEntity> wrapper) {
        List<WmsOutNoticeEntity> list = wmsOutNoticeMapper.selectList(wrapper);
        List<WmsOutNoticeExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutNoticeExcelVO vo = new WmsOutNoticeExcelVO();
            WmsOutNoticeEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
            result.add(vo);
        }
        return result;
    }

    /**
     * 查询明细列表
     *
     * @param params 查询条件
     * @param ids    指定ID
     */
    @Override
    public List<WmsOutNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        List<WmsOutNoticeDetailEntity> list = wmsOutNoticeDetailMapper.fetchDetails(params, ids);
        List<WmsOutNoticeDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsOutNoticeDetailExcelVO vo = new WmsOutNoticeDetailExcelVO();
            WmsOutNoticeDetailEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setNoticeOutNum(entity.getNoticeOutNum() + entity.getMatUnit());
            vo.setOutNum(entity.getOutNum() + entity.getMatUnit());
            vo.setNotOutNum(entity.getNotOutNum() + entity.getMatUnit());
            vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
            result.add(vo);
        }
        return result;
    }

    /**
     * 作废生产领料出库通知单-通过业务单据
     */
    @Override
    public Boolean voidObjByDocumentId(Long documentId, String documentTable) {
        List<WmsOutNoticeEntity> entityList = wmsOutNoticeMapper.selectList(new LambdaQueryWrapper<WmsOutNoticeEntity>()
                .eq(WmsOutNoticeEntity::getDocumentId, documentId)
                .eq(WmsOutNoticeEntity::getDocumentTable, documentTable)
        );
        if (entityList.size() == 0) {
            return Boolean.TRUE;
        }
        List<Long> ids = entityList.stream().map(BaseEntity::getId).toList();
        // 判断是否存在未完成的出库单
        Long count = wmsOutBillMapper.selectCount(new LambdaQueryWrapper<WmsOutBillEntity>()
                .in(WmsOutBillEntity::getNoticeId, ids)
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                .ne(WmsOutBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
        );
        if (count > 0) {
            throw new CheckedException("存在未完成的出库单，不可作废！");
        }

        wmsOutNoticeMapper.update(new LambdaUpdateWrapper<WmsOutNoticeEntity>()
                .set(WmsOutNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .in(BaseEntity::getId, ids));
        return Boolean.TRUE;
    }
}
