package com.kitex.mom.mes.dto;

/**
 * 生产标签的来源、版式和原件回执合同；制造身份沿原SN，发放及意图编号仅用于打印审计。
 */
public final class MesProductionLabelDTO {
	private MesProductionLabelDTO() {
	}

	/**
	 * 按原派工及实际工位读取标签来源。
	 *
	 * @param kind         标签来源种类：TASK工单、PIECE可信制造单件。
	 * @param arrangeId    实际组织内原派工主键文本。
	 * @param worksiteCode 原派工当前实际接收工位编码，仍须核本人资格。
	 * @param identifier   原工单码或可信制造出生原SN；标签流程不重新生成制造身份。
	 */
	public record Query(String kind, String arrangeId, String worksiteCode, String identifier) {
	}

	/**
	 * 真实工单或可信制造出生的标签来源。
	 *
	 * @param kind                     标签来源种类：TASK工单、PIECE可信制造单件。
	 * @param tenantId                 经过认证的实际租户主键文本。
	 * @param factoryCode              经过授权的实际工厂编码。
	 * @param actorId                  服务器当前实名操作者主键文本。
	 * @param arrangeId                实际组织内原派工主键文本。
	 * @param arrangeCode              原派工业务编码。
	 * @param taskId                   实际生产工单主键文本。
	 * @param taskCode                 真实生产工单编码，限定实际租户和工厂。
	 * @param operationCode            原派工对应的工序任务编码。
	 * @param routeNodeCode            冻结路线内稳定节点编码，区分重复主工序。
	 * @param worksiteCode             原派工当前实际接收工位编码，仍须核本人资格。
	 * @param materialCode             原工单冻结产品或原实际物料编码。
	 * @param quantityUnit             冻结工程或制造出生的原数量单位。
	 * @param issuedQuantity           工单原下达量或单件数量1的精确十进制文本，单位为quantityUnit。
	 * @param identifier               原工单码或可信制造出生原SN；标签流程不重新生成制造身份。
	 * @param manufacturingFingerprint 可信制造出生原文指纹，仅单件来源具有，工单来源为空。
	 * @param engineeringReleaseId     原冻结工程发布主键文本。
	 * @param engineeringHash          原冻结工程内容指纹。
	 */
	public record Source(String kind, String tenantId, String factoryCode, String actorId, String arrangeId,
	                     String arrangeCode, String taskId, String taskCode, String operationCode,
	                     String routeNodeCode, String worksiteCode, String materialCode, String quantityUnit,
	                     String issuedQuantity, String identifier, String manufacturingFingerprint,
	                     String engineeringReleaseId, String engineeringHash) {
	}

	/**
	 * 服务端核对后的标签来源及指纹。
	 *
	 * @param source      该结果关联的完整冻结来源。
	 * @param fingerprint 不可变原文的SHA256指纹。
	 */
	public record Context(Source source, String fingerprint) {
	}

	/**
	 * 当前实际工厂合法模板的冻结摘要。
	 *
	 * @param id               当前实际工厂的模板主键文本。
	 * @param code             当前实际工厂的启用模板编码。
	 * @param name             当前模板中文业务名称。
	 * @param businessTypeCode 模板合法业务类型：MES_TASK_LABEL或MES_PIECE_LABEL。
	 * @param fingerprint      不可变原文的SHA256指纹。
	 */
	public record Template(String id, String code, String name, String businessTypeCode, String fingerprint) {
	}

	/**
	 * 稳定原请求号下的标签发放命令。
	 *
	 * @param requestId                   首次生成并保持的稳定原请求号；未知结果按此号核对。
	 * @param source                      该结果关联的完整冻结来源。
	 * @param templateId                  当前实际工厂的明确模板主键文本。
	 * @param expectedSourceFingerprint   预览时核对的服务器来源指纹；变化必须重读。
	 * @param expectedTemplateFingerprint 预览时核对的原模板指纹；变化必须重新预览。
	 */
	public record IssueCommand(String requestId, Query source, String templateId,
	                           String expectedSourceFingerprint, String expectedTemplateFingerprint) {
	}

	/**
	 * 服务端来源、模板及受控版式预览。
	 *
	 * @param context  已核验的真实来源及其指纹。
	 * @param template 被冻结或待核对的实际模板元数据。
	 * @param html     公共打印服务返回的受控HTML；不代表物理纸张打印完成。
	 */
	public record Preview(Context context, Template template, String html) {
	}

	/**
	 * 不改变制造SN的不可变标签发放原件。
	 *
	 * @param schema            标签原件或打印意图的固定协议版本。
	 * @param issueId           标签发放原件主键文本；不是制造SN。
	 * @param tenantId          经过认证的实际租户主键文本。
	 * @param factoryCode       经过授权的实际工厂编码。
	 * @param actorId           服务器当前实名操作者主键文本。
	 * @param registeredAt      服务器首次登记的UTC时刻文本。
	 * @param command           原始稳定命令，响应丢失后不得换号换内容。
	 * @param source            该结果关联的完整冻结来源。
	 * @param sourceFingerprint 冻结来源原文指纹。
	 * @param template          被冻结或待核对的实际模板元数据。
	 * @param html              公共打印服务返回的受控HTML；不代表物理纸张打印完成。
	 */
	public record Issue(String schema, String issueId, String tenantId, String factoryCode, String actorId,
	                    String registeredAt, IssueCommand command, Source source, String sourceFingerprint,
	                    Template template, String html) {
	}

	/**
	 * 原件下首次打印或有原因重打的意图命令。
	 *
	 * @param requestId                首次生成并保持的稳定原请求号；未知结果按此号核对。
	 * @param issueId                  标签发放原件主键文本；不是制造SN。
	 * @param worksiteCode             原派工当前实际接收工位编码，仍须核本人资格。
	 * @param expectedIssueFingerprint 本次打印所核对的标签发放原件指纹。
	 * @param previousIntentId         重打必须引用最近一次原意图；首次打印为空。
	 * @param reason                   500字内实际重打原因；首次打印为空。
	 */
	public record IntentCommand(String requestId, String issueId, String worksiteCode,
	                            String expectedIssueFingerprint, String previousIntentId, String reason) {
	}

	/**
	 * 向浏览器交付前登记的打印意图原件。
	 *
	 * @param schema           标签原件或打印意图的固定协议版本。
	 * @param intentId         打印意图审计主键文本，不证明驱动或纸张成功。
	 * @param issueId          标签发放原件主键文本；不是制造SN。
	 * @param ordinal          同一标签原件内连续的意图序号文本，从1开始。
	 * @param tenantId         经过认证的实际租户主键文本。
	 * @param factoryCode      经过授权的实际工厂编码。
	 * @param actorId          服务器当前实名操作者主键文本。
	 * @param registeredAt     服务器首次登记的UTC时刻文本。
	 * @param kind             意图种类：PRINT_INTENT首次打印、REPRINT_INTENT有原因重打。
	 * @param command          原始稳定命令，响应丢失后不得换号换内容。
	 * @param issueFingerprint 关联标签原件的不可变指纹。
	 */
	public record Intent(String schema, String intentId, String issueId, String ordinal, String tenantId,
	                     String factoryCode, String actorId, String registeredAt, String kind,
	                     IntentCommand command, String issueFingerprint) {
	}

	/**
	 * 首次受理原文及其不可变摘要。
	 *
	 * @param original    首次服务端受理的完整原件。
	 * @param fingerprint 不可变原文的SHA256指纹。
	 */
	public record Original<T>(T original, String fingerprint) {
	}

	/**
	 * 本人原号的APPLIED或ABSENT只读核对结果。
	 *
	 * @param state   设备观察状态：RUN运行、STOP停止、UNKNOWN未知。
	 * @param receipt 查到的本人原请求回执；ABSENT时为空，仍不能推断未执行。
	 */
	public record Resolution<T>(String state, Original<T> receipt) {
	}
}
