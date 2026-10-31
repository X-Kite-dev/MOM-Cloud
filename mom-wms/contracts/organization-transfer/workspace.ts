import { computed, ref, type UnwrapRef } from 'vue'
import {
  organizationError,
  cloneOrganization,
  organizationId,
  organizationLabels,
  type OrganizationAction,
  type OrganizationDetail,
  type OrganizationInput,
  type OrganizationPolicy,
  type OrganizationBill,
} from './contract'
import {
  buildOrganizationAction,
  buildOrganizationCreate,
  organizationPhysicalRows,
  type OrganizationPhysicalForm,
  type OrganizationStock,
} from './model'
import type { createOrganizationOperation } from './operation'
interface Api {
  policy(warehouse: string): Promise<any>
  savePolicy(body: Record<string, unknown>): Promise<any>
  directory(warehouse: string): Promise<any>
  stocks(
    warehouse: string,
    material: string,
    barcode: string,
    beforeStockId: string | null,
  ): Promise<any>
  page(warehouse: string, billNo: string, state: string, page: number): Promise<any>
  detail(warehouse: string, id: string): Promise<any>
  history(warehouse: string, id: string, page: number): Promise<any>
  effects(warehouse: string, id: string, page: number): Promise<any>
}
export function createOrganizationWorkspace(
  session: ReturnType<typeof createOrganizationOperation>,
  api: Api,
  warehouse: () => string,
) {
  const policy = ref<OrganizationPolicy | null>(null)
  const partners = ref<OrganizationPolicy[]>([])
  const records = ref<OrganizationBill[]>([])
  const total = ref(0)
  const page = ref(1)
  const billNo = ref('')
  const state = ref('')
  const material = ref('')
  const stockBarcode = ref('')
  const stockCursor = ref<string | null>(null)
  const stocks = ref<OrganizationStock[]>([])
  const selected = ref<{ stock: OrganizationStock; quantity: string | number }[]>([])
  const targetPolicyId = ref('')
  const agreement = ref('')
  const createReason = ref('')
  const detail = ref<OrganizationDetail | null>(null)
  const action = ref<OrganizationAction | ''>('')
  const actionReason = ref('')
  const evidence = ref('')
  const physicalRows = ref<OrganizationPhysicalForm[]>([])
  const error = ref('')
  const reads = ref<Record<string, boolean>>({})
  const policySaving = ref(false)
  const historyPage = ref(1)
  const effectPage = ref(1)
  const policyForm = ref({ companyCode: '', companyName: '', enabled: true, crossCompany: false })
  const confirming = ref(false)
  const locked = computed(
    () =>
      !session.ready.value ||
      session.busy.value ||
      !!session.pending.value ||
      policySaving.value ||
      confirming.value,
  )
  const canCreate = computed(
    () => !!policy.value?.enabled && !!session.context.value?.permissions.includes('CREATE'),
  )
  const counters = new Map<string, number>()
  async function read<T>(name: string, request: () => Promise<any>, apply: (value: T) => void) {
    let ticket = -1
    const attempt = (counters.get(name) || 0) + 1
    counters.set(name, attempt)
    reads.value = { ...reads.value, [name]: true }
    error.value = ''
    try {
      ticket = session.capture()
      const response = await request()
      if (!session.current(ticket) || counters.get(name) !== attempt) return false
      if (response?.code !== 0) throw new Error(response?.msg || '读取组织调拨数据失败')
      apply(response.data)
      return true
    } catch (problem) {
      if (ticket < 0 || (session.current(ticket) && counters.get(name) === attempt))
        error.value = organizationError(problem, '读取失败，请重试')
      return false
    } finally {
      if (counters.get(name) === attempt) reads.value = { ...reads.value, [name]: false }
    }
  }
  async function loadPolicy() {
    return read<OrganizationPolicy | null>(
      'policy',
      () => api.policy(warehouse()),
      (value) => {
        policy.value = value
        policyForm.value = value
          ? {
              companyCode: value.companyCode,
              companyName: value.companyName,
              enabled: value.enabled,
              crossCompany: value.crossCompany,
            }
          : { companyCode: '', companyName: '', enabled: true, crossCompany: false }
      },
    )
  }
  async function loadList(append = false) {
    const captured = { number: billNo.value, state: state.value, page: page.value }
    return read<{ records: OrganizationBill[]; total: number | string }>(
      'list',
      () => api.page(warehouse(), captured.number, captured.state, captured.page),
      (value) => {
        if (
          captured.number !== billNo.value ||
          captured.state !== state.value ||
          captured.page !== page.value
        )
          return
        let count = Number.NaN
        if (typeof value?.total === 'number') count = value.total
        else if (typeof value?.total === 'string' && /^\d+$/.test(value.total))
          count = Number(value.total)
        if (!Array.isArray(value?.records) || !Number.isSafeInteger(count) || count < 0)
          throw new Error('调拨列表响应不完整')
        records.value = append
          ? [
              ...records.value,
              ...value.records.filter((row) => !records.value.some((old) => old.id === row.id)),
            ]
          : value.records
        total.value = count
      },
    )
  }
  async function loadDirectory() {
    if (!canCreate.value) {
      partners.value = []
      return false
    }
    return read<OrganizationPolicy[]>(
      'directory',
      () => api.directory(warehouse()),
      (value) => {
        if (!Array.isArray(value)) throw new Error('合作仓库目录未形成有效结果')
        partners.value = value
      },
    )
  }
  function resetCreate() {
    selected.value = []
    targetPolicyId.value = ''
    agreement.value = ''
    createReason.value = ''
    material.value = ''
    stockBarcode.value = ''
    stockCursor.value = null
    stocks.value = []
  }
  async function prepare() {
    resetCreate()
    counters.clear()
    records.value = []
    total.value = 0
    detail.value = null
    policy.value = null
    partners.value = []
    stocks.value = []
    selected.value = []
    action.value = ''
    physicalRows.value = []
    reads.value = {}
    error.value = ''
    page.value = 1
    await session.initialize()
    if (!session.ready.value) return
    await Promise.all([loadPolicy(), loadList()])
    if (session.ready.value) await loadDirectory()
  }
  async function loadStocks(append = false) {
    if (!canCreate.value || locked.value || (append && (!stockCursor.value || reads.value.stocks)))
      return false
    const filter = material.value
    const barcode = stockBarcode.value
    const cursor = append ? stockCursor.value : null
    if (!append) {
      stocks.value = []
      stockCursor.value = null
    }
    return read<{ records: OrganizationStock[]; nextCursor: string | null }>(
      'stocks',
      () => api.stocks(warehouse(), filter, barcode, cursor),
      (value) => {
        if (filter !== material.value || barcode !== stockBarcode.value) return
        if (!Array.isArray(value?.records)) throw new Error('源库存列表无效')
        const next = value.nextCursor === null ? null : organizationId(value.nextCursor)
        stocks.value = append
          ? [
              ...stocks.value,
              ...value.records.filter((row) => !stocks.value.some((old) => old.id === row.id)),
            ]
          : value.records
        stockCursor.value = next
      },
    )
  }

  async function openDetail(id: string) {
    action.value = ''
    actionReason.value = ''
    evidence.value = ''
    physicalRows.value = []
    detail.value = null
    historyPage.value = 1
    effectPage.value = 1
    return read<OrganizationDetail>(
      'detail',
      () => api.detail(warehouse(), organizationId(id)),
      (value) => {
        if (
          organizationId(value?.bill?.id) !== id ||
          !Array.isArray(value.lines) ||
          !Array.isArray(value.actions)
        )
          throw new Error('组织调拨明细未完整返回')
        detail.value = value
        if (value.actions.length) selectAction(value.actions[0])
      },
    )
  }
  function selectAction(value: OrganizationAction) {
    if (!detail.value || !detail.value.actions.includes(value)) return
    action.value = value
    actionReason.value = ''
    evidence.value = ''
    physicalRows.value = organizationPhysicalRows(detail.value, value)
  }
  function createInput(): OrganizationInput {
    const partner = partners.value.find((row) => row.id === targetPolicyId.value)
    if (!policy.value || !partner) throw new Error('请选择已发布的合作仓库')
    return buildOrganizationCreate(
      policy.value,
      partner,
      selected.value,
      agreement.value,
      createReason.value,
    )
  }
  function actionInput(): OrganizationInput {
    if (!detail.value || !action.value) throw new Error('请重新读取单据并选择本次操作')
    return buildOrganizationAction(
      detail.value,
      action.value,
      physicalRows.value,
      actionReason.value,
      evidence.value,
    )
  }
  async function afterOperation() {
    if (session.pending.value || !session.last.value || !session.ready.value) return false
    const ticket = session.capture()
    await loadList()
    if (session.current(ticket) && session.last.value.billId)
      await openDetail(organizationId(session.last.value.billId))
    return session.current(ticket)
  }
  async function perform(input: OrganizationInput, caption: string) {
    if (locked.value) return false
    const ticket = session.capture()
    await session.perform(cloneOrganization(input), caption)
    if (!session.current(ticket) || session.pending.value || !session.last.value) return false
    await afterOperation()
    return true
  }
  async function confirmAndPerform(
    input: OrganizationInput,
    caption: string,
    confirm: () => Promise<boolean>,
  ) {
    if (locked.value) return false
    const ticket = session.capture()
    const frozen = cloneOrganization(input)
    confirming.value = true
    let accepted = false
    try {
      accepted = await confirm()
    } finally {
      confirming.value = false
    }
    if (!accepted || !session.current(ticket)) return false
    return perform(frozen, caption)
  }
  async function resolve(action: 'query' | 'retry' | 'abandon', reason = '') {
    const ticket = session.capture()
    await session.resolve(action, reason)
    if (!session.current(ticket)) return false
    return afterOperation()
  }
  async function savePolicy() {
    if (locked.value || !session.context.value?.canConfigure) return false
    let ticket = -1
    policySaving.value = true
    error.value = ''
    try {
      ticket = session.capture()
      const context = session.context.value
      if (!context) throw new Error('请重新核对当前组织身份')
      const response = await api.savePolicy({
        ...cloneOrganization(policyForm.value),
        warehouseCode: warehouse(),
        expectedVersion: policy.value?.version || 0,
        tenantId: context.tenantId,
        operatorId: context.operatorId,
        factoryCode: context.factoryCode,
      })
      if (!session.current(ticket)) return false
      if (response?.code !== 0)
        throw new Error(response?.msg || '组织设置保存失败，请重新读取配置核对')
      await loadPolicy()
      await loadDirectory()
      return true
    } catch (problem) {
      if (ticket < 0 || session.current(ticket))
        error.value = organizationError(problem, '配置结果未确认，请重新读取')
      return false
    } finally {
      policySaving.value = false
    }
  }
  async function moreHistory() {
    if (!detail.value || reads.value.history) return
    const id = detail.value.bill.id
    const next = historyPage.value + 1
    return read<any[]>(
      'history',
      () => api.history(warehouse(), id, next),
      (value) => {
        if (detail.value?.bill.id !== id || !Array.isArray(value)) return
        const existing = detail.value
        existing.history.push(
          ...value.filter(
            (item) => !existing.history.some((old) => old.requestId === item.requestId),
          ),
        )
        detail.value.historyTruncated = value.length === 100
        historyPage.value = next
      },
    )
  }
  async function moreEffects() {
    if (!detail.value || reads.value.effects) return
    const id = detail.value.bill.id
    const next = effectPage.value + 1
    return read<any[]>(
      'effects',
      () => api.effects(warehouse(), id, next),
      (value) => {
        if (detail.value?.bill.id !== id || !Array.isArray(value)) return
        const existing = detail.value
        existing.effects.push(
          ...value.filter((item) => !existing.effects.some((old) => old.flowId === item.flowId)),
        )
        detail.value.effectsTruncated = value.length === 100
        effectPage.value = next
      },
    )
  }
  return {
    session,
    confirming,
    confirmAndPerform,
    policy,
    partners,
    records,
    total,
    page,
    billNo,
    state,
    material,
    stockBarcode,
    stockCursor,
    stocks,
    selected,
    targetPolicyId,
    agreement,
    createReason,
    detail,
    action,
    actionReason,
    evidence,
    physicalRows,
    error,
    reads,
    policySaving,
    policyForm,
    locked,
    canCreate,
    resetCreate,
    prepare,
    loadList,
    loadPolicy,
    loadDirectory,
    loadStocks,
    openDetail,
    selectAction,
    createInput,
    actionInput,
    perform,
    resolve,
    savePolicy,
    moreHistory,
    moreEffects,
    labels: organizationLabels,
  }
}

export type OrganizationWorkspace = UnwrapRef<ReturnType<typeof createOrganizationWorkspace>>
