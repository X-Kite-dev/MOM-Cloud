import {
  organizationId,
  organizationText,
  type OrganizationAction,
  type OrganizationDetail,
  type OrganizationInput,
  type OrganizationPolicy,
} from './contract'
export interface OrganizationStock {
  id: string
  matCode: string
  matName: string
  matBatch: string
  matUnit: string
  matNum: number
  matBarcode: string
  serialManaged: boolean
  siteCode: string
  containerCode: string
}
export interface OrganizationPhysicalForm {
  lineId: string
  quantity: number | string
  siteCode: string
  containerCode: string
  barcode: string
}
export const organizationReasonRequired = (action: OrganizationAction) =>
  [
    'CANCEL',
    'REQUEST_RETURN',
    'PROPOSE_EXCEPTION',
    'APPROVE_EXCEPTION',
    'REJECT_EXCEPTION',
  ].includes(action)
export const organizationEvidenceRequired = (action: OrganizationAction) =>
  ['SHIP', 'RECEIVE', 'RETURN', 'PROPOSE_EXCEPTION', 'APPROVE_EXCEPTION'].includes(action)
export const organizationPhysical = (action: OrganizationAction) =>
  ['SHIP', 'RECEIVE', 'RETURN'].includes(action)
export function organizationPhysicalRows(
  detail: OrganizationDetail,
  action: OrganizationAction,
): OrganizationPhysicalForm[] {
  return detail.lines
    .filter((line) => action === 'SHIP' || line.remaining > 0)
    .map((line) => ({
      lineId: line.id,
      quantity: action === 'SHIP' ? line.quantity : '',
      siteCode: '',
      containerCode: '',
      barcode: '',
    }))
}
export function buildOrganizationCreate(
  own: OrganizationPolicy,
  target: OrganizationPolicy,
  selected: { stock: OrganizationStock; quantity: string | number }[],
  agreement: string,
  reason: string,
): OrganizationInput {
  if (!own?.enabled || !target?.enabled || own.factoryCode === target.factoryCode)
    throw new Error('请先配置本仓库存组织，并选择其他工厂已发布的合作仓库')
  if (
    own.companyCode !== target.companyCode &&
    (!own.crossCompany || !target.crossCompany || !organizationText(agreement))
  )
    throw new Error('跨公司须双方启用，并填写业务依据')
  const ids = new Set<string>()
  const lines = selected.map(({ stock, quantity }) => {
    const id = organizationId(stock.id)
    const amount = Number(quantity)
    if (
      ids.has(id) ||
      !Number.isFinite(amount) ||
      amount <= 0 ||
      amount > stock.matNum ||
      (stock.serialManaged && amount !== 1)
    )
      throw new Error('申请数量须大于0、不超过可调拨量；单件序列号每行1件')
    ids.add(id)
    return {
      stockId: id,
      lineId: null,
      quantity: amount,
      siteCode: '',
      containerCode: '',
      barcode: '',
    }
  })
  if (!lines.length || lines.length > 500) throw new Error('请选择1至500行发出库存')
  return {
    action: 'CREATE',
    billId: null,
    expectedVersion: 0,
    targetPolicyId: organizationId(target.id),
    agreementRef: organizationText(agreement),
    reason: organizationText(reason),
    evidence: '',
    lines,
  }
}
export function buildOrganizationAction(
  detail: OrganizationDetail,
  action: OrganizationAction,
  rows: OrganizationPhysicalForm[],
  reason: string,
  evidence: string,
): OrganizationInput {
  if (!detail || !detail.actions.includes(action))
    throw new Error('当前单据已变化或没有本次操作权限，请重新读取')
  if (organizationReasonRequired(action) && !organizationText(reason))
    throw new Error('请填写本次处理原因')
  if (organizationEvidenceRequired(action) && !organizationText(evidence))
    throw new Error('请填写实物交接、验收或差异凭证')
  const lines: OrganizationInput['lines'] = []
  const ids = new Set<string>()
  if (organizationPhysical(action)) {
    for (const row of rows) {
      if (action !== 'SHIP' && (row.quantity === '' || Number(row.quantity) === 0)) continue
      const original = detail.lines.find((line) => line.id === row.lineId)
      const amount = Number(row.quantity)
      if (
        !original ||
        ids.has(row.lineId) ||
        !Number.isFinite(amount) ||
        amount <= 0 ||
        (action === 'SHIP' ? amount !== original.quantity : amount > original.remaining)
      )
        throw new Error('本次数量须与发出任务一致或不超过未结数量')
      if (
        original.serialManaged &&
        (amount !== 1 || organizationText(row.barcode) !== original.barcode)
      )
        throw new Error('请扫描本行原始序列号核对实物')
      if (action !== 'SHIP' && !organizationText(row.siteCode))
        throw new Error('请逐行填写本侧实际收货库位')
      ids.add(row.lineId)
      lines.push({
        lineId: organizationId(row.lineId),
        stockId: null,
        quantity: amount,
        siteCode: action === 'SHIP' ? '' : organizationText(row.siteCode),
        containerCode: action === 'SHIP' ? '' : organizationText(row.containerCode),
        barcode: organizationText(row.barcode),
      })
    }
    if (!lines.length || (action === 'SHIP' && lines.length !== detail.lines.length))
      throw new Error('请完整核对发出明细，或填写至少一行实收数量')
  }
  return {
    action,
    billId: organizationId(detail.bill.id),
    expectedVersion: detail.bill.version,
    targetPolicyId: null,
    agreementRef: '',
    reason: organizationText(reason),
    evidence: organizationText(evidence),
    lines,
  }
}
