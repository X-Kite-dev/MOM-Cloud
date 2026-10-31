import { quantityText } from './quantity'

export { quantityText } from './quantity'

export interface CorrectionScope {
  serverUrl: string
  tenantId: string
  factoryCode: string
  operatorCode: string
}

export function correctionSorted(value: any): string {
  if (Array.isArray(value)) return `[${value.map(correctionSorted).join(',')}]`
  if (value !== null && typeof value === 'object')
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${correctionSorted(value[key])}`)
      .join(',')}}`
  return JSON.stringify(value)
}
const sorted = correctionSorted

export interface CorrectionCommand {
  requestId: string
  warehouseCode: string
  noticeId: string
  reason: string
  qmsDecisionReversalId: string | null
  qmsDecisionAuthorizationCode: string | null
  stockSelections: Array<{ stockId: string; quantity: string }>
}
export interface CorrectionReceipt {
  contractVersion: number
  status: 'REGISTERED' | 'NOT_CONFIRMED'
  requestId: string
  tenantId: string
  factoryCode: string
  operatorCode: string
  warehouseCode: string
  noticeId: string
  correctionId: string | null
  correctionNo: string | null
  appliedTime: string | null
  quantity: string | null
  commandFingerprint: string | null
  requestPayload: string | null
}

const text = (value: unknown) => (typeof value === 'string' ? value.trim() : '')
function identifier(value: unknown): string {
  if (
    typeof value !== 'string' ||
    !/^[1-9]\d{0,18}$/.test(value) ||
    (value.length === 19 && value > '9223372036854775807')
  )
    throw new Error('原申请或库存标识不完整，请重新核对')
  return value
}

export function selectionTotal(lines: CorrectionCommand['stockSelections']): string {
  let sum = '0'
  for (const line of lines) {
    const [whole, fraction = ''] = quantityText(line.quantity).split('.')
    const addend = whole + fraction.padEnd(8, '0')
    let carry = 0
    let next = ''
    for (let i = sum.length - 1, j = addend.length - 1; i >= 0 || j >= 0 || carry; i--, j--) {
      const digit = Number(sum[i] || 0) + Number(addend[j] || 0) + carry
      next = String(digit % 10) + next
      carry = Math.floor(digit / 10)
    }
    sum = next.replace(/^0+(?=\d)/, '')
  }
  const padded = sum.padStart(9, '0')
  return quantityText(`${padded.slice(0, -8)}.${padded.slice(-8)}`)
}

export function normalizeCorrection(command: CorrectionCommand): CorrectionCommand {
  if (
    !command ||
    !/^[A-Za-z0-9][A-Za-z0-9:._-]{7,63}$/.test(command.requestId) ||
    !text(command.warehouseCode) ||
    command.warehouseCode.length > 64 ||
    /[*?]/.test(command.warehouseCode) ||
    !text(command.reason) ||
    command.reason.trim().length > 500 ||
    !Array.isArray(command.stockSelections) ||
    !command.stockSelections.length ||
    command.stockSelections.length > 1000
  )
    throw new Error('请核对原通知、仓库、原因和所选库存')
  const authorizationId =
    command.qmsDecisionReversalId == null ? null : identifier(command.qmsDecisionReversalId)
  const authorizationCode = text(command.qmsDecisionAuthorizationCode) || null
  if ((authorizationId === null) !== (authorizationCode === null))
    throw new Error('质量逆向授权信息不完整，请重新核对')
  const seen = new Set<string>()
  const stockSelections = command.stockSelections.map((line) => {
    const stockId = identifier(line?.stockId)
    if (seen.has(stockId)) throw new Error('同一库存不能重复选择')
    seen.add(stockId)
    return { stockId, quantity: quantityText(line.quantity) }
  })
  selectionTotal(stockSelections)
  return {
    requestId: command.requestId,
    warehouseCode: command.warehouseCode.trim(),
    noticeId: identifier(command.noticeId),
    reason: command.reason.trim(),
    qmsDecisionReversalId: authorizationId,
    qmsDecisionAuthorizationCode: authorizationCode,
    stockSelections,
  }
}

export function correctionPayload(command: CorrectionCommand) {
  const normalized = normalizeCorrection(command)
  return {
    noticeId: normalized.noticeId,
    requestId: normalized.requestId,
    reason: normalized.reason,
    rejectReason: null,
    qmsDecisionReversalId: normalized.qmsDecisionReversalId,
    qmsDecisionAuthorizationCode: normalized.qmsDecisionAuthorizationCode,
    stockSelections: normalized.stockSelections,
  }
}

export function verifyCorrectionReceipt(
  response: any,
  command: CorrectionCommand,
  scope: CorrectionScope,
): CorrectionReceipt {
  const row: CorrectionReceipt = response?.data
  if (
    response?.code !== 0 ||
    row?.contractVersion !== 1 ||
    row.requestId !== command.requestId ||
    row.tenantId !== scope.tenantId ||
    row.factoryCode !== scope.factoryCode ||
    row.operatorCode !== scope.operatorCode ||
    row.warehouseCode !== command.warehouseCode ||
    row.noticeId !== command.noticeId ||
    !['REGISTERED', 'NOT_CONFIRMED'].includes(row.status)
  )
    throw new Error(response?.msg || '原申请登记结果与当前账号或原请求不一致')
  if (row.status === 'NOT_CONFIRMED') return row
  identifier(row.correctionId)
  if (
    !text(row.correctionNo) ||
    !text(row.appliedTime) ||
    typeof row.commandFingerprint !== 'string' ||
    !/^[a-f0-9]{64}$/.test(row.commandFingerprint) ||
    typeof row.requestPayload !== 'string' ||
    typeof row.quantity !== 'string' ||
    quantityText(row.quantity) !== selectionTotal(command.stockSelections) ||
    sorted(JSON.parse(row.requestPayload)) !== sorted(correctionPayload(command))
  )
    throw new Error('原申请登记证据不完整，请保留原请求核对')
  return JSON.parse(JSON.stringify(row))
}
