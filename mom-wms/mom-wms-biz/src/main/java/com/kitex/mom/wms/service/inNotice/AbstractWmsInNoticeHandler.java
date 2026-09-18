package com.kitex.mom.wms.service.inNotice;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
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
import com.kitex.mom.common.seq.api.feign.RemoteSeqConfigService;
import com.kitex.mom.mdm.entity.MdmMaterialEntity;
import com.kitex.mom.mdm.entity.MdmWarehouseEntity;
import com.kitex.mom.mdm.feign.RemoteMaterialService;
import com.kitex.mom.mdm.feign.RemoteWarehouseService;
import com.kitex.mom.wms.dto.WmsInNoticeDetailQueryDTO;
import com.kitex.mom.wms.entity.WmsInBillEntity;
import com.kitex.mom.wms.entity.WmsInNoticeDetailEntity;
import com.kitex.mom.wms.entity.WmsInNoticeEntity;
import com.kitex.mom.wms.enums.InBillTypeEnum;
import com.kitex.mom.wms.enums.InOutBillStateEnum;
import com.kitex.mom.wms.enums.InOutNoticeStateEnum;
import com.kitex.mom.wms.enums.OtherInBillTypeEnum;
import com.kitex.mom.wms.mapper.WmsInBillMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeDetailMapper;
import com.kitex.mom.wms.mapper.WmsInNoticeMapper;
import com.kitex.mom.wms.vo.WmsInNoticeDetailExcelVO;
import com.kitex.mom.wms.vo.WmsInNoticeExcelVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public abstract class AbstractWmsInNoticeHandler
        implements WmsInNoticeHandler<WmsInNoticeEntity, WmsInNoticeDetailEntity> {

    @Autowired
    protected WmsInNoticeDetailMapper wmsInNoticeDetailMapper;
    @Autowired
    private WmsInNoticeMapper wmsInNoticeMapper;
    @Autowired
    private WmsInBillMapper wmsInBillMapper;

    @Autowired
    private RemoteWarehouseService remoteWarehouseService;

    @Autowired
    private RemoteSeqConfigService remoteSeqConfigService;

    @Autowired
    private RemoteMaterialService remoteMaterialService;

    /**
     * @param wmsInNotice 通知单信息
     * @return
     */
    @Override
    @Transactional
    public Boolean saveDeep(WmsInNoticeEntity wmsInNotice) {

        // 先校验业务载荷，再由WMS自身生成指纹，调用方不能用伪造指纹绕过同号异载荷校验。
        checkData(wmsInNotice);

        if (StrUtil.isNotBlank(wmsInNotice.getSourceRequestId())) {
            String requestId = wmsInNotice.getSourceRequestId().trim();
            if (requestId.length() > 200) throw new CheckedException("入库通知来源幂等号长度不能超过200");
            String sourceFingerprint = noticeFingerprint(wmsInNotice);
            wmsInNoticeMapper.lockSourceRequest(requestId);
            WmsInNoticeEntity existing = wmsInNoticeMapper.selectOne(new LambdaQueryWrapper<WmsInNoticeEntity>()
                    .eq(WmsInNoticeEntity::getSourceRequestId, requestId), false);
            if (existing != null) {
                if (!Objects.equals(existing.getSourceRequestFingerprint(), sourceFingerprint)) {
                    throw new CheckedException("入库通知来源幂等号已使用且载荷不一致：" + requestId);
                }
                wmsInNotice.setId(existing.getId());
                return true;
            }
            wmsInNotice.setSourceRequestId(requestId);
            wmsInNotice.setSourceRequestFingerprint(sourceFingerprint);
        }

        // 进行校验
        checkWarehouse(wmsInNotice.getWarehouseCode(), wmsInNotice.getBillType());

        // 设置默认状态
        setDefaultState(wmsInNotice);

        // 设置初始数量
        List<WmsInNoticeDetailEntity> details = wmsInNotice.getDetailList();
        Double sum = details.stream()
                .map(WmsInNoticeDetailEntity::getNoticeInNum)
                .filter(Objects::nonNull)
                .reduce(0.0, Double::sum);
        wmsInNotice.setNoticeInNum(sum);
        wmsInNotice.setAllNotInNum(sum);
        wmsInNotice.setAllInNum(0.0);

        // 保存入库通知单信息
        int result = wmsInNoticeMapper.insert(wmsInNotice);
        if (result <= 0) {
            throw new CheckedException("保存入库通知单失败");
        }
        if (CollUtil.isNotEmpty(details)) {
            /* 处理明细信息start */
            for (WmsInNoticeDetailEntity detail : details) {
                detail.setNoticeId(wmsInNotice.getId());
                detail.setWorkshop(wmsInNotice.getWorkshop());
                detail.setNotInNum(detail.getNoticeInNum());
                detail.setInNum(0.0);
                detail.setWarehouseCode(wmsInNotice.getWarehouseCode());
                detail.setWarehouseName(wmsInNotice.getWarehouseName());
                detail.setFactoryCode(wmsInNotice.getFactoryCode());
            }
            /* 处理明细信息end */
            // 新增明细
            wmsInNoticeDetailMapper.insert(details);
        }
        return true;
    }

    private String noticeFingerprint(WmsInNoticeEntity notice) {
        try {
            List<String> lines = Optional.ofNullable(notice.getDetailList()).orElse(Collections.emptyList()).stream()
                    .map(detail -> String.join("~", String.valueOf(detail.getMatCode()),
                            String.valueOf(detail.getRequirementMatCode()), String.valueOf(detail.getNoticeInNum()),
                            String.valueOf(detail.getWarehouseCode()), String.valueOf(detail.getMatUnit())))
                    .sorted().toList();
            String raw = String.join("|", String.valueOf(notice.getBillType()), String.valueOf(notice.getDocumentTable()),
                    String.valueOf(notice.getDocumentId()), String.valueOf(notice.getWarehouseCode()),
                    String.valueOf(notice.getWarehouseName()), String.valueOf(notice.getRemark()), String.join(";", lines));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new CheckedException("入库通知来源指纹生成失败");
        }
    }

    /**
     * 作废
     *
     * @param id 通知单id
     */
    @Override
    public void voidObjs(Long id) {
        WmsInNoticeEntity noticeEntity = Optional.ofNullable(wmsInNoticeMapper.selectById(id))
                .orElseThrow(() -> new CheckedException("单据不存在！"));

        // 判断是否存在未完成的入库单
        Long count = wmsInBillMapper.selectCount(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, id)
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.FINISHED.getCode())
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
        );
        if (count > 0) {
            throw new CheckedException("存在未完成的入库单，不可作废！");
        }

        wmsInNoticeMapper.update(new LambdaUpdateWrapper<WmsInNoticeEntity>()
                .set(WmsInNoticeEntity::getState, InOutNoticeStateEnum.CANCELED.getCode())
                .eq(BaseEntity::getId, id));

        voidObjsBack(noticeEntity);
    }

    @Override
    public void setDefaultState(WmsInNoticeEntity notice) {
        notice.setState(InOutNoticeStateEnum.READY.getCode());
    }

    @Override
    public Boolean updateDeep(WmsInNoticeEntity wmsInNotice) {
        return null;
    }

    /**
     * @param page    分页参数
     * @param wrapper 查询条件
     * @return
     */
    @Override
    public Page<WmsInNoticeEntity> page(Page page, QueryWrapper<WmsInNoticeEntity> wrapper) {
        Page result = wmsInNoticeMapper.selectPage(page, wrapper);
        return result;
    }

    @Override
    public Map<String, Long> getCount(QueryWrapper<WmsInNoticeEntity> wrapper) {
        List<WmsInNoticeEntity> wmsInNoticeEntities = wmsInNoticeMapper.selectList(wrapper);
        return wmsInNoticeEntities
                .stream()
                .collect(Collectors.groupingBy(WmsInNoticeEntity::getState, Collectors.counting()));
    }

    /**
     * 分页查询详情列表
     *
     * @param page   分页参数
     * @param params 查询条件
     * @return
     */
    @Override
    public Page<WmsInNoticeDetailEntity> pageDetails(Page page, WmsInNoticeDetailQueryDTO params) {
        return wmsInNoticeDetailMapper.fetchDetails(page, params, null);
    }

    /**
     * 根据id查询数据
     *
     * @param id
     * @return
     */
    @Override
    public WmsInNoticeEntity selectById(Long id) {
        WmsInNoticeEntity wmsInNoticeEntity = Optional.ofNullable(wmsInNoticeMapper.selectById(id)).orElseThrow(() -> new CheckedException("入库通知单查找失败，请确认单号！"));
        List<WmsInNoticeDetailEntity> wmsInNoticeDetailEntities = wmsInNoticeDetailMapper.selectList(Wrappers.<WmsInNoticeDetailEntity>lambdaQuery().eq(WmsInNoticeDetailEntity::getNoticeId, id));
        //是否有无空白批次信息，如果没有批次信息，进入填充逻辑
        Long count = wmsInNoticeDetailEntities.stream().filter(item -> StrUtil.isBlank(item.getMatBatch())).count();
        if (count.compareTo(0L) > 0) {
            //查询仓库批次配置信息，如果开启批次并且批次为空，自动填充批次信息
            R<MdmWarehouseEntity> byCode = remoteWarehouseService.getByCode(wmsInNoticeEntity.getWarehouseCode());
            if (byCode.getCode() != 0) {
                throw new CheckedException("查询仓库信息失败，请检查主数据服务是否正常运行！");
            }
            MdmWarehouseEntity warehouse = byCode.getData();
            //开启批次，自动填充没有批次的数据
            if (warehouse.getIsBatch() == 1) {
                String warehouse_pc = remoteSeqConfigService.create("warehouse_pc").getData();
                wmsInNoticeDetailEntities.forEach(item -> {
                    item.setMatBatch(StrUtil.isNotBlank(item.getMatBatch()) ? item.getMatBatch() : warehouse_pc);
                });
            }
        }
        //有效期是否有效（不为空），如果存在无效有效期，查询物料中的有效期进行覆盖
        Long countVp = wmsInNoticeDetailEntities.stream().filter(item -> ObjectUtil.isEmpty(item.getMatValidPeriod())).count();
        if (countVp.compareTo(0L) > 0) {
            R<List<MdmMaterialEntity>> matData = remoteMaterialService.getList();
            if (matData.getCode() != 0) {
                throw new CheckedException("查询物料信息失败，请检查主数据服务是否正常运行！");
            }
            List<MdmMaterialEntity> matList = matData.getData();
            Map<String, MdmMaterialEntity> matMap = matList.stream().collect(Collectors.toMap(MdmMaterialEntity::getMaterialCode, Function.identity(), (k1, k2) -> k1));
            wmsInNoticeDetailEntities.forEach(item -> {
                if (ObjectUtil.isEmpty(item.getMatValidPeriod())) {
                    MdmMaterialEntity mdmMaterial = matMap.get(item.getMatCode());
                    if (ObjectUtil.isNotEmpty(mdmMaterial)) {
                        item.setMatValidPeriod(mdmMaterial.getExpirationDay());
                        if (ObjectUtil.isNotEmpty(item.getMatProduceDate())) {
                            item.setMatValidEndDate(ObjectUtil.isNotEmpty(item.getMatValidPeriod()) ? item.getMatProduceDate().plusDays(item.getMatValidPeriod()) : null);
                        }
                    }
                }
            });
        }

        wmsInNoticeEntity.setDetailList(wmsInNoticeDetailEntities);
        return wmsInNoticeEntity;
    }

    /**
     * 根据id查询数据-App
     *
     * @param id
     * @return
     */
    @Override
    public WmsInNoticeEntity selectByIdApp(Long id) {
        WmsInNoticeEntity wmsInNoticeEntity = selectById(id);
        // 根据通知单号查询出库单号（app操作时需要用到）
        List<WmsInBillEntity> inBills = wmsInBillMapper.selectList(new LambdaQueryWrapper<WmsInBillEntity>()
                .eq(WmsInBillEntity::getNoticeId, id)
                .ne(WmsInBillEntity::getState, InOutBillStateEnum.PASSED.getCode())
                .orderByDesc(BaseCore::getCreateTime)
                .last("limit 1")
        );
        if (inBills.size() > 0) {
            wmsInNoticeEntity.setBillNo(inBills.get(0).getBillNo());
        }
        return wmsInNoticeEntity;
    }

    @Override
    public void checkData(WmsInNoticeEntity wmsInNotice) {
        // 校验必填数据是否存在
        if (CollUtil.isEmpty(wmsInNotice.getDetailList())) {
            throw new CheckedException("明细数据不能为空");
        }
        Optional.ofNullable(wmsInNotice.getWarehouseCode()).orElseThrow(() -> new CheckedException(String.format("提交数据缺少[%s]", "仓库编码")));
        Optional.ofNullable(wmsInNotice.getBillType()).orElseThrow(() -> new CheckedException(String.format("提交数据缺少[%s]", "单据类型")));

        // 校验这个仓库是否可以操作本业务
        Assert.isTrue(InBillTypeEnum.isValid(wmsInNotice.getBillType()) || OtherInBillTypeEnum.isValid(wmsInNotice.getBillType()), "无效的单据类型：" + wmsInNotice.getBillType());
    }

    @Override
    public Boolean updateState(Long id, String state) {
        wmsInNoticeMapper.update(null,
                Wrappers.<WmsInNoticeEntity>lambdaUpdate()
                        .eq(WmsInNoticeEntity::getId, id)
                        .set(WmsInNoticeEntity::getState, state));
        return Boolean.TRUE;
    }

    /**
     * 更新通知单明细信息
     *
     * @param id      通知单id
     * @param details 通知单详情列表
     * @return
     */
    @Override
    public Boolean updateDetails(Long id, List<WmsInNoticeDetailEntity> details) {
        // 校验是否能更新
        WmsInNoticeEntity wmsInNoticeEntity = wmsInNoticeMapper.selectById(id);
        // 校验状态
        Assert.isTrue(wmsInNoticeEntity.getState().equals("0"), "通知单已审核，不能修改");
        // 重新设置通知单id
        List<WmsInNoticeDetailEntity> list = details.stream().peek(item -> item.setNoticeId(id)).toList();
        wmsInNoticeDetailMapper.delete(new LambdaQueryWrapper<>());
        wmsInNoticeDetailMapper.insertOrUpdate(list);

        return Boolean.TRUE;
    }

    /**
     * @param warehouseCode 仓库编码
     * @param billType      单据类型
     * @return
     */
    @Override
    public Boolean checkWarehouse(String warehouseCode, String billType) {

        return true;
    }

    /**
     * @param ids
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean removeDeep(String[] ids) {
        // 删除通知单
        if (CollUtil.isEmpty(Arrays.asList(ids))) {
            return Boolean.TRUE;
        }
        wmsInNoticeMapper.deleteByIds(CollUtil.toList(ids));
        wmsInNoticeDetailMapper
                .delete(Wrappers.<WmsInNoticeDetailEntity>lambdaQuery().in(WmsInNoticeDetailEntity::getNoticeId, ids));
        return Boolean.TRUE;
    }

    /**
     * 查询主表列表
     */
    @Override
    public List<WmsInNoticeExcelVO> list(QueryWrapper<WmsInNoticeEntity> wrapper) {
        List<WmsInNoticeEntity> list = wmsInNoticeMapper.selectList(wrapper);
        List<WmsInNoticeExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsInNoticeExcelVO vo = new WmsInNoticeExcelVO();
            WmsInNoticeEntity entity = list.get(i);
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
    public List<WmsInNoticeDetailExcelVO> listDetails(WmsInNoticeDetailQueryDTO params, Long[] ids) {
        List<WmsInNoticeDetailEntity> list = wmsInNoticeDetailMapper.fetchDetails(params, ids);
        List<WmsInNoticeDetailExcelVO> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            WmsInNoticeDetailExcelVO vo = new WmsInNoticeDetailExcelVO();
            WmsInNoticeDetailEntity entity = list.get(i);
            BeanUtil.copyProperties(entity, vo);
            vo.setRowNo(i + 1);
            vo.setNoticeInNum(entity.getNoticeInNum() + entity.getMatUnit());
            vo.setInNum(entity.getInNum() + entity.getMatUnit());
            vo.setNotInNum(entity.getNotInNum() + entity.getMatUnit());
            vo.setState(InOutNoticeStateEnum.getByCode(entity.getState()).getName());
            result.add(vo);
        }
        return result;
    }


}
