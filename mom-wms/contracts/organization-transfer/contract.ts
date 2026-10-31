export type OrganizationAction =
  | 'CREATE'
  | 'CONFIRM'
  | 'SHIP'
  | 'RECEIVE'
  | 'CANCEL'
  | 'REQUEST_RETURN'
  | 'RETURN'
  | 'PROPOSE_EXCEPTION'
  | 'APPROVE_EXCEPTION'
  | 'REJECT_EXCEPTION'
export const organizationLabels: Record<OrganizationAction, string> = {
  CREATE: '申请调拨',
  CONFIRM: '接收方确认',
  SHIP: '确认发出',
  RECEIVE: '登记接收',
  CANCEL: '发出前作废',
  REQUEST_RETURN: '申请实物退回',
  RETURN: '登记实物退回',
  PROPOSE_EXCEPTION: '申报未结差异',
  APPROVE_EXCEPTION: '确认差异结案',
  REJECT_EXCEPTION: '退回差异申请',
}
export const organizationStates: Record<string, string> = {
  PENDING_CONFIRMATION: '待接收方确认',
  READY_TO_SHIP: '待发出',
  IN_TRANSIT: '在途',
  PARTIALLY_RECEIVED: '部分接收',
  COMPLETED: '已完成',
  CANCELLED: '已作废',
  RETURN_PENDING: '待实物退回',
  RETURNED: '已全部退回',
  CLOSED_WITH_RETURN: '部分接收并退回结案',
  EXCEPTION_PENDING: '差异待对方确认',
  CLOSED_WITH_DIFFERENCE: '差异结案',
}
export interface OrganizationContext {
  contractVersion: number
  tenantId: string
  operatorId: string
  operatorCode: string
  factoryCode: string
  warehouseCode: string
  permissions: OrganizationAction[]
  canConfigure: boolean
}
export interface OrganizationInputLine {
  lineId?: string | null
  stockId?: string | null
  quantity: number
  siteCode?: string
  containerCode?: string
  barcode?: string
}
export interface OrganizationCommand {
  requestId: string
  tenantId: string
  operatorId: string
  factoryCode: string
  warehouseCode: string
  action: OrganizationAction
  billId: string | null
  expectedVersion: number
  targetPolicyId: string | null
  agreementRef: string
  reason: string
  evidence: string
  lines: OrganizationInputLine[]
}
export type OrganizationInput = Omit<
  OrganizationCommand,
  'requestId' | 'tenantId' | 'operatorId' | 'factoryCode' | 'warehouseCode'
>
export interface OrganizationEffect {
  lineId: string
  factoryCode: string
  warehouseCode: string
  action: string
  stockId: string
  flowId: string
  quantity: number
  time: string
}
export interface OrganizationReceipt
  extends Pick<
    OrganizationContext,
    'contractVersion' | 'tenantId' | 'operatorId' | 'operatorCode' | 'factoryCode' | 'warehouseCode'
  > {
  requestId: string
  action: OrganizationAction
  status: 'APPLIED' | 'ABANDONED' | 'NOT_FOUND'
  billId: string | null
  billNo: string
  state: string
  version: number | null
  requestPayload: string | null
  effects: OrganizationEffect[]
  completedTime: string | null
  reason: string
}
export interface OrganizationPolicy {
  id?: string
  tenantId?: string
  factoryCode: string
  warehouseCode: string
  warehouseName: string
  companyCode: string
  companyName: string
  enabled: boolean
  crossCompany: boolean
  version: number
}
export interface OrganizationLine {
  id: string
  sourceStockId: string
  materialCode: string
  materialName: string
  batch: string
  unit: string
  barcode: string
  serialManaged: boolean
  qualityStatus: string
  qualityResult: string
  quantity: number
  received: number
  returned: number
  exception: number
  remaining: number
}
export interface OrganizationBill {
  id: string
  billNo: string
  state: string
  version: number
  sourceFactoryCode: string
  sourceWarehouseCode: string
  sourceWarehouseName: string
  sourceCompanyCode: string
  sourceCompanyName: string
  targetFactoryCode: string
  targetWarehouseCode: string
  targetWarehouseName: string
  targetCompanyCode: string
  targetCompanyName: string
  agreementRef: string
  remark: string
  createdBy: string
  confirmedBy: string
  createdTime: string
}
export interface OrganizationHistory {
  requestId: string
  action: OrganizationAction
  factoryCode: string
  operatorCode: string
  state: string
  reason: string
  evidence: string
  time: string
}
export interface OrganizationDetail {
  bill: OrganizationBill
  lines: OrganizationLine[]
  actions: OrganizationAction[]
  effects: OrganizationEffect[]
  history: OrganizationHistory[]
  effectsTruncated: boolean
  historyTruncated: boolean
}
// Frozen commands retain JSON transport semantics for older handheld runtimes.
export const cloneOrganization = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export const organizationText = (value: unknown) => (typeof value === 'string' ? value.trim() : '')
export function organizationSorted(value: any): string {
  if (Array.isArray(value)) return `[${value.map((item) => organizationSorted(item)).join(',')}]`
  if (value && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${organizationSorted(value[key])}`)
      .join(',')}}`
  return JSON.stringify(value)
}
export function organizationId(value: unknown): string {
  const id = String(value)
  if (
    (typeof value !== 'string' && !(typeof value === 'number' && Number.isSafeInteger(value))) ||
    !/^[1-9]\d{0,18}$/.test(id) ||
    (id.length === 19 && id > '9223372036854775807')
  )
    throw new Error('组织调拨来源标识无法核对，请重新读取')
  return id
}
export function canonicalOrganization(command: OrganizationCommand) {
  if (
    !command ||
    !Object.keys(organizationLabels).includes(command.action) ||
    !/^[A-Za-z0-9][A-Za-z0-9_-]{15,63}$/.test(command.requestId) ||
    !organizationText(command.warehouseCode) ||
    command.warehouseCode.length > 100 ||
    /[*?]/.test(command.warehouseCode) ||
    !organizationText(command.factoryCode) ||
    !Number.isInteger(command.expectedVersion) ||
    command.expectedVersion < 0
  )
    throw new Error('组织调拨原请求不完整，请重新准备')
  const create = command.action === 'CREATE'
  const physical = ['CREATE', 'SHIP', 'RECEIVE', 'RETURN'].includes(command.action)
  if (
    (create && (command.billId !== null || !command.targetPolicyId)) ||
    (!create && (!command.billId || command.targetPolicyId !== null)) ||
    !Array.isArray(command.lines) ||
    (physical ? !command.lines.length || command.lines.length > 500 : command.lines.length > 0)
  )
    throw new Error('本次操作的单据或实物明细无效')
  if (
    (create ? command.expectedVersion !== 0 : command.expectedVersion < 1) ||
    organizationText(command.agreementRef).length > 200 ||
    organizationText(command.reason).length > 1000 ||
    organizationText(command.evidence).length > 1000
  )
    throw new Error('当前版本或交接信息长度不符合办理要求')
  const ids = new Set<string>()
  const lines = command.lines
    .map((line) => {
      const id = organizationId(create ? line.stockId : line.lineId)
      if (
        ids.has(id) ||
        !Number.isFinite(line.quantity) ||
        line.quantity <= 0 ||
        (create
          ? line.lineId !== null && line.lineId !== undefined
          : line.stockId !== null && line.stockId !== undefined)
      )
        throw new Error('实物明细重复或数量无效')
      if (
        organizationText(line.siteCode).length > 100 ||
        organizationText(line.containerCode).length > 100 ||
        organizationText(line.barcode).length > 200
      )
        throw new Error('库位、容器或条码内容过长')
      ids.add(id)
      return {
        lineId:
          line.lineId === null || line.lineId === undefined ? '' : organizationId(line.lineId),
        stockId:
          line.stockId === null || line.stockId === undefined ? '' : organizationId(line.stockId),
        quantity: line.quantity,
        siteCode: organizationText(line.siteCode),
        containerCode: organizationText(line.containerCode),
        barcode: organizationText(line.barcode),
      }
    })
    .sort((a, b) => (`${a.lineId}:${a.stockId}` < `${b.lineId}:${b.stockId}` ? -1 : 1))
  return {
    tenantId: organizationId(command.tenantId),
    operatorId: organizationId(command.operatorId),
    factoryCode: organizationText(command.factoryCode),
    action: command.action,
    billId:
      command.billId === null || command.billId === undefined ? '' : organizationId(command.billId),
    warehouseCode: organizationText(command.warehouseCode),
    expectedVersion: command.expectedVersion,
    targetPolicyId:
      command.targetPolicyId === null || command.targetPolicyId === undefined
        ? ''
        : organizationId(command.targetPolicyId),
    agreementRef: organizationText(command.agreementRef),
    reason: organizationText(command.reason),
    evidence: organizationText(command.evidence),
    lines,
  }
}
export function verifyOrganization(
  response: any,
  context: OrganizationContext,
  command: OrganizationCommand,
): OrganizationReceipt {
  const result = response?.data
  if (
    response?.code !== 0 ||
    result?.contractVersion !== 1 ||
    organizationId(result.tenantId) !== organizationId(context.tenantId) ||
    organizationId(result.operatorId) !== organizationId(context.operatorId) ||
    result.operatorCode !== context.operatorCode ||
    result.factoryCode !== context.factoryCode ||
    result.warehouseCode !== context.warehouseCode ||
    result.requestId !== command.requestId ||
    !['APPLIED', 'ABANDONED', 'NOT_FOUND'].includes(result.status)
  )
    throw new Error(response?.msg || '原操作结果与当前组织或身份不一致')
  if (result.status === 'NOT_FOUND') return result
  if (
    result.action !== command.action ||
    !organizationText(result.completedTime) ||
    typeof result.requestPayload !== 'string' ||
    organizationSorted(JSON.parse(result.requestPayload)) !==
      organizationSorted(canonicalOrganization(command)) ||
    !Array.isArray(result.effects)
  )
    throw new Error('原操作回执与冻结内容不一致，请继续核对')
  if (result.status === 'ABANDONED') {
    if (
      result.effects.length ||
      !organizationText(result.reason) ||
      (command.billId !== null && organizationId(result.billId) !== command.billId) ||
      (command.billId === null && result.billId !== null)
    )
      throw new Error('取消原请求的回执不完整')
    return result
  }
  const billId = organizationId(result.billId)
  if (
    result.billNo !== `OT-${billId}` ||
    (command.billId !== null && billId !== command.billId) ||
    result.version !== command.expectedVersion + 1 ||
    !Object.keys(organizationStates).includes(result.state)
  )
    throw new Error('组织调拨原结果不完整，请继续核对')
  const states: Record<OrganizationAction, string[]> = {
    CREATE: ['PENDING_CONFIRMATION'],
    CONFIRM: ['READY_TO_SHIP'],
    SHIP: ['IN_TRANSIT'],
    RECEIVE: ['PARTIALLY_RECEIVED', 'COMPLETED'],
    CANCEL: ['CANCELLED'],
    REQUEST_RETURN: ['RETURN_PENDING'],
    RETURN: ['RETURN_PENDING', 'RETURNED', 'CLOSED_WITH_RETURN'],
    PROPOSE_EXCEPTION: ['EXCEPTION_PENDING'],
    APPROVE_EXCEPTION: ['CLOSED_WITH_DIFFERENCE'],
    REJECT_EXCEPTION: ['IN_TRANSIT', 'PARTIALLY_RECEIVED', 'RETURN_PENDING'],
  }
  if (!states[command.action].includes(result.state)) throw new Error('原操作结果与业务阶段不一致')
  const physical = ['SHIP', 'RECEIVE', 'RETURN'].includes(command.action)
  if (
    physical ? !result.effects.length || result.effects.length > 500 : result.effects.length !== 0
  )
    throw new Error('原操作缺少实际库存效果凭证')
  const lines = new Set<string>()
  const flows = new Set<string>()
  for (const effect of result.effects) {
    const line = organizationId(effect.lineId)
    const flow = organizationId(effect.flowId)
    organizationId(effect.stockId)
    if (
      lines.has(line) ||
      flows.has(flow) ||
      effect.factoryCode !== context.factoryCode ||
      effect.warehouseCode !== context.warehouseCode ||
      effect.action !== command.action ||
      !Number.isFinite(effect.quantity) ||
      effect.quantity <= 0 ||
      !organizationText(effect.time)
    )
      throw new Error('实际实物效果无法与本次操作核对')
    lines.add(line)
    flows.add(flow)
    if (!command.lines.some((input) => input.lineId === line && input.quantity === effect.quantity))
      throw new Error('实际接收数量与原请求不一致')
  }
  if (
    ['SHIP', 'RECEIVE', 'RETURN'].includes(command.action) &&
    result.effects.length !== command.lines.length
  )
    throw new Error('实际接收明细未完整核对')
  return result
}

export function organizationError(error: unknown, fallback: string): string {
  if (error && typeof error === 'object') {
    const detail = error as { msg?: unknown; message?: unknown }
    if (typeof detail.msg === 'string' && detail.msg.trim()) return detail.msg
    if (typeof detail.message === 'string' && detail.message.trim()) return detail.message
  }
  return fallback
}
