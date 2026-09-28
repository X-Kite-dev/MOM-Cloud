package com.kitex.mom.mes.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.kitex.mom.mes.dto.MesQualityDispositionDTO;
import com.kitex.mom.mes.entity.MesScrapMaterialEntity;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 报废品管理服务
 *
 * @author system
 * @since 2025-01-27
 */
public interface MesScrapMaterialService extends IService<MesScrapMaterialEntity> {

    /**
     * 分页查询
     *
     * @param pageParam 分页参数
     * @param query     查询条件
     * @return 分页结果
     */
    IPage<MesScrapMaterialEntity> queryPage(Page<MesScrapMaterialEntity> pageParam, MesScrapMaterialEntity query);

    void create(MesScrapMaterialEntity entity);

    void edit(MesScrapMaterialEntity entity);

    void deletePending(List<Long> ids);

    void createFromQualityDisposition(MesQualityDispositionDTO command);

    /**
     * 导出报废品管理数据
     *
     * @param query    查询条件
     * @param response 响应对象
     */
    void export(MesScrapMaterialEntity query, HttpServletResponse response);

    /**
     * 导入报废品管理数据
     *
     * @param file 导入文件
     * @return 导入结果
     */
    String importData(MultipartFile file);

    /**
     * 下载导入模板
     *
     * @param response 响应对象
     */
    void template(HttpServletResponse response);

    /**
     * 审核报废品
     *
     * @param id 主键id
     */
    void audit(Long id);

    /**
     * 取消审核报废品
     *
     * @param id 主键id
     */
    void cancelAudit(Long id);
}
