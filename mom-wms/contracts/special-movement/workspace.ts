import { computed, ref, watch } from 'vue'
import {
  body,
  check,
  clone,
  decimal,
  errorText,
  id,
  sorted,
  validatePolicy,
  validateView,
  type Action,
  type CloseLine,
  type ExecutionLine,
  type Input,
  type Location,
  type Material,
  type Plan,
  type Policy,
  type Stock,
  type View,
} from './contract'
import {
  actionInput,
  allowedActions,
  blankExecution,
  blankPlan,
  closureRows,
  createInput,
  originalFlows,
  returnable,
} from './model'
import type { createSpecialOperation, Guard } from './operation'
export interface SpecialApi {
  page(
    warehouse: string,
    kind: string,
    state: string,
    reference: string,
    page: number,
    guard: Guard,
  ): Promise<unknown>
  detail(warehouse: string, id: string, guard: Guard): Promise<unknown>
  materials(warehouse: string, keyword: string, page: number, guard: Guard): Promise<unknown>
  sites(warehouse: string, guard: Guard): Promise<unknown>
  stocks(
    warehouse: string,
    orderId: string,
    lineId: string,
    source: string | null,
    barcode: string,
    cursor: string | null,
    guard: Guard,
  ): Promise<unknown>
  savePolicy?(
    policy: { warehouseCode: string; expectedVersion: string; rules: Policy['rules'] },
    guard: Guard,
  ): Promise<unknown>
}
export function createSpecialWorkspace(
  session: ReturnType<typeof createSpecialOperation>,
  api: SpecialApi,
  warehouse: () => string,
  mobile = false,
) {
  const records = ref<View[]>([])
  const total = ref(0)
  const page = ref(1)
  const kind = ref('')
  const state = ref('')
  const reference = ref('')
  const detail = ref<View | null>(null)
  const plan = ref<Plan>(blankPlan())
  const policy = ref<Policy | null>(null)
  const error = ref('')
  const reads = ref<Record<string, boolean>>({})
  const confirming = ref(false)
  const policySaving = ref(false)
  const action = ref<Action | ' '>(' ')
  const reason = ref('')
  const evidence = ref('')
  const lines = ref<ExecutionLine[]>([])
  const closeLines = ref<CloseLine[]>([])
  const materials = ref<Material[]>([])
  const materialKeyword = ref('')
  const materialPage = ref(1)
  const materialTotal = ref(0)
  const sites = ref<Location[]>([])
  const stocks = ref<Stock[]>([])
  const stockCursor = ref<string | null>(null)
  const stocksLoaded = ref(false)
  const stockBarcode = ref('')
  const lineId = ref('')
  const sourceFlowId = ref<string | null>(null)
  const locked = computed(
    () =>
      !session.ready.value ||
      session.busy.value ||
      !!session.pending.value ||
      confirming.value ||
      policySaving.value,
  )
  // 申请选项只取已保存规则；规则抽屉的未保存开关不改变可办范围。
  // 历史列表仍使用完整类型目录，停用类型的旧单不能因此失去查询入口。
  const enabledKinds = computed(
    () =>
      session.context.value?.policy.rules.filter((row) => row.enabled).map((row) => row.kind) ?? [],
  )
  const createRule = computed(() =>
    session.context.value?.policy.rules.find((row) => row.kind === plan.value.kind),
  )
  const canCreate = computed(
    () =>
      !!session.context.value?.warehouseEnabled &&
      session.context.value?.permissions.includes('add') &&
      (!mobile || session.context.value.mobileEnabled) &&
      enabledKinds.value.length > 0,
  )
  const availableActions = computed(() =>
    detail.value && session.context.value
      ? allowedActions(detail.value, session.context.value, mobile)
      : [],
  )
  const physical = computed(() => ['EXECUTE', 'RECEIVE_OUTPUT', 'RETURN'].includes(action.value))
  const sourceOptions = computed(() =>
    detail.value
      ? originalFlows(detail.value).filter(
          (row) => decimal(returnable(detail.value!, row)) > BigInt('0'),
        )
      : [],
  )
  const selectedSource = computed(() =>
    sourceOptions.value.find((row) => row.id === sourceFlowId.value),
  )
  const inbound = computed(
    () =>
      action.value === 'RECEIVE_OUTPUT' ||
      (action.value === 'RETURN'
        ? selectedSource.value?.direction === 'OUT'
        : detail.value?.order.plan.direction === 'IN'),
  )
  const selectableLines = computed(
    () =>
      detail.value?.balances.filter((row) =>
        action.value === 'RECEIVE_OUTPUT' ? row.output : !row.output,
      ) || [],
  )
  const selectionKey = () =>
    sorted([
      detail.value?.order.id,
      detail.value?.order.version,
      action.value,
      lineId.value,
      sourceFlowId.value,
      stockBarcode.value,
    ])
  const counters = new Map<string, number>()
  let selectionGeneration = 0
  const stockLimits = new Map<string, bigint>()
  // 同一单据切换动作或物料后再切回，也视为新的选库上下文，迟到扫码不得回填。
  watch(
    selectionKey,
    () => {
      selectionGeneration++
      stocksLoaded.value = false
      stocks.value = []
      stockCursor.value = null
      counters.delete('stocks')
      reads.value = { ...reads.value, stocks: false }
    },
    { flush: 'sync' },
  )
  function captureSelection() {
    check(detail.value && physical.value, '请先选择本次实物作业')
    const epoch = session.capture()
    const selection = selectionGeneration
    return () => session.current(epoch) && selectionGeneration === selection && !locked.value
  }
  // 读取票据不随刷新清零，避免旧请求的finally释放新请求的加载状态。
  let nextReadTicket = 0
  let preparation = 0
  let loadedListFilters: string | null = null
  let loadedMaterialKeyword: string | null = null
  async function read<T>(
    name: string,
    request: (guard: Guard) => Promise<unknown>,
    apply: (value: T) => void | boolean,
  ) {
    const attempt = ++nextReadTicket
    counters.set(name, attempt)
    reads.value = { ...reads.value, [name]: true }
    error.value = ''
    let epoch = -1
    try {
      epoch = session.capture()
      const response = await request(session.guard(epoch))
      if (!session.current(epoch) || counters.get(name) !== attempt) return false
      return apply(body<T>(response)) !== false
    } catch (problem) {
      if (epoch >= 0 && session.current(epoch) && counters.get(name) === attempt)
        error.value = errorText(problem, '读取失败，请重试')
      return false
    } finally {
      if (counters.get(name) === attempt) reads.value = { ...reads.value, [name]: false }
    }
  }
  /** 页码只在同条件的有效响应安装后推进；失败保留上一页，追加不得跨条件混入。 */
  async function loadList(append = false, requestedPage = page.value) {
    const filters = sorted({ kind: kind.value, state: state.value, reference: reference.value })
    if (append && (loadedListFilters !== filters || requestedPage !== page.value + 1)) {
      error.value = '查询条件已变化，请重新查询后加载更多'
      return false
    }
    const query = {
      kind: kind.value,
      state: state.value,
      reference: reference.value,
      page: requestedPage,
    }
    return read<{ records: View[]; total: number; page: number; size: number }>(
      'list',
      (guard) => api.page(warehouse(), query.kind, query.state, query.reference, query.page, guard),
      (value) => {
        if (
          filters !==
          sorted({
            kind: kind.value,
            state: state.value,
            reference: reference.value,
          })
        ) {
          return false
        }
        check(
          Array.isArray(value?.records) &&
            Number.isSafeInteger(value.total) &&
            value.total >= 0 &&
            value.page === query.page,
          '列表响应不完整',
        )
        const rows = value.records.map((row) => validateView(row, session.context.value!))
        records.value = append
          ? [
              ...records.value,
              ...rows.filter((row) => !records.value.some((old) => old.order.id === row.order.id)),
            ]
          : rows
        total.value = value.total
        page.value = query.page
        loadedListFilters = filters
      },
    )
  }
  function loadPage(requestedPage: number) {
    return loadList(false, requestedPage)
  }
  function resetAction() {
    action.value = ' '
    reason.value = ''
    evidence.value = ''
    lines.value = []
    closeLines.value = []
    lineId.value = ''
    sourceFlowId.value = null
    stocks.value = []
    stockCursor.value = null
    stockBarcode.value = ''
    stockLimits.clear()
    stocksLoaded.value = false
  }
  function resetCreate() {
    plan.value = blankPlan()
    // 仅启用免费或外协时直接选中可办理类型，避免默认停用的备件导致提交后才拒绝。
    const firstKind = enabledKinds.value[0]
    if (firstKind) {
      plan.value.kind = firstKind
    }
    materials.value = []
    materialKeyword.value = ''
    materialPage.value = 1
    materialTotal.value = 0
    loadedMaterialKeyword = null
  }
  /** 清理旧范围展示和读取票据，真实库存及持久未决请求仍由原操作会话管理。 */
  function clearWorkspace() {
    counters.clear()
    reads.value = {}
    confirming.value = false
    policySaving.value = false
    records.value = []
    total.value = 0
    page.value = 1
    loadedListFilters = null
    detail.value = null
    policy.value = null
    sites.value = []
    error.value = ''
    resetAction()
    resetCreate()
  }
  watch(
    session.context,
    (value) => {
      if (value === null) clearWorkspace()
    },
    { flush: 'sync' },
  )
  /** 绑定本次初始化代次，旧prepare不能在后来身份准备成功后继续安装其规则或列表。 */
  async function prepare() {
    const attempt = ++preparation
    clearWorkspace()
    const initializing = session.initialize()
    const epoch = session.version()
    await initializing
    if (attempt !== preparation || !session.current(epoch) || !session.ready.value) return false
    policy.value = clone(session.context.value!.policy)
    await loadList()
    return attempt === preparation && session.current(epoch)
  }
  async function openDetail(orderId: string) {
    if (locked.value) return false
    resetAction()
    detail.value = null
    return read<View>(
      'detail',
      (guard) => api.detail(warehouse(), id(orderId), guard),
      (value) => {
        detail.value = validateView(value, session.context.value!)
      },
    )
  }
  /** 物料目录与业务列表采用相同的成功后推进规则，切换关键字不能继续追加旧页。 */
  async function loadMaterials(append = false, requestedPage = materialPage.value) {
    const query = { keyword: materialKeyword.value, page: requestedPage }
    if (
      append &&
      (loadedMaterialKeyword !== query.keyword || requestedPage !== materialPage.value + 1)
    ) {
      error.value = '物料条件已变化，请重新查询后加载更多'
      return false
    }
    return read<{ records: Material[]; total: number }>(
      'materials',
      (guard) => api.materials(warehouse(), query.keyword, query.page, guard),
      (value) => {
        if (query.keyword !== materialKeyword.value) {
          return false
        }
        check(
          Array.isArray(value?.records) &&
            Number.isSafeInteger(value.total) &&
            value.total >= 0 &&
            value.records.every(
              (row) =>
                row &&
                typeof row.code === 'string' &&
                !!row.code &&
                typeof row.name === 'string' &&
                typeof row.unit === 'string' &&
                !!row.unit,
            ),
          '物料目录不完整',
        )
        materials.value = append
          ? [
              ...materials.value,
              ...value.records.filter(
                (row) => !materials.value.some((old) => old.code === row.code),
              ),
            ]
          : value.records
        materialTotal.value = value.total
        materialPage.value = query.page
        loadedMaterialKeyword = query.keyword
      },
    )
  }
  function loadMaterialPage(requestedPage: number) {
    return loadMaterials(false, requestedPage)
  }
  function addMaterial(material: Material, output = false) {
    const group = output ? plan.value.outputs : plan.value.lines
    check(!group.some((row) => row.materialCode === material.code), '该物料已在计划中，请调整数量')
    check(group.length < 200, '单类最多200行')
    group.push({
      lineId: null,
      materialCode: material.code,
      materialName: material.name,
      unit: material.unit,
      quantity: '',
    })
  }
  async function chooseAction(value: Action) {
    check(availableActions.value.includes(value), '当前申请不能办理此动作')
    resetAction()
    action.value = value
    if (value === 'CLOSE') closeLines.value = closureRows(detail.value!)
    if (physical.value) {
      lineId.value =
        selectableLines.value.find((row) => decimal(row.remaining) > BigInt('0'))?.lineId || ''
      await loadSites()
    }
  }
  async function loadSites() {
    return read<Location[]>(
      'sites',
      (guard) => api.sites(warehouse(), guard),
      (value) => {
        check(
          Array.isArray(value) &&
            value.every(
              (row) => typeof row?.code === 'string' && !!row.code && typeof row.name === 'string',
            ),
          '库位目录不完整',
        )
        sites.value = value
      },
    )
  }
  function selectSource(sourceId: string) {
    check(detail.value, '请先读取申请')
    const source = sourceOptions.value.find((row) => row.id === sourceId)
    check(source, '请选择可退回的原实际流水')
    if (lines.value.length) {
      const first = sourceOptions.value.find((row) => row.id === lines.value[0].sourceFlowId)
      check(
        first && first.direction === source.direction,
        '收回入库与退回对方请分别办理，先完成或移除本次明细',
      )
    }
    sourceFlowId.value = source.id
    lineId.value = source.lineId
    stocks.value = []
    stockCursor.value = null
  }
  async function loadStocks(append = false) {
    if (reads.value.stocks) {
      return false
    }
    check(detail.value && lineId.value && !inbound.value, '请先选择出库计划或退回原件')
    const key = selectionKey()
    const cursor = append ? stockCursor.value : null
    if (!append) {
      stocks.value = []
      stockCursor.value = null
      stocksLoaded.value = false
    }
    return read<{ records: Stock[]; nextCursor: string | null }>(
      'stocks',
      (guard) =>
        api.stocks(
          warehouse(),
          detail.value!.order.id,
          lineId.value,
          sourceFlowId.value,
          stockBarcode.value,
          cursor,
          guard,
        ),
      (value) => {
        if (key !== selectionKey()) {
          return false
        }
        check(Array.isArray(value?.records), '库存候选未完整读取')
        for (const row of value.records) {
          id(row.stockId)
          decimal(row.quantity, false)
          decimal(row.available)
          check(
            row.materialCode ===
              detail.value!.balances.find((line) => line.lineId === lineId.value)?.materialCode &&
              row.unit ===
                detail.value!.balances.find((line) => line.lineId === lineId.value)?.unit &&
              decimal(row.available) <= decimal(row.quantity),
            '库存物料、单位或可用数量与当前计划不一致',
          )
        }
        if (value.nextCursor !== null) id(value.nextCursor)
        stocks.value = append
          ? [
              ...stocks.value,
              ...value.records.filter(
                (row) => !stocks.value.some((old) => old.stockId === row.stockId),
              ),
            ]
          : value.records
        stockCursor.value = value.nextCursor
        stocksLoaded.value = true
      },
    )
  }
  function addExecution(stock?: Stock) {
    check(detail.value && physical.value, '请先选择本次动作')
    if (action.value === 'RETURN') check(selectedSource.value, '请选择原实际收发记录')
    check(lineId.value, '请选择计划物料')
    check(lines.value.length < 200, '本次最多200行')
    const row = blankExecution(lineId.value, sourceFlowId.value)
    if (!inbound.value) {
      check(
        stock && stocks.value.some((current) => sorted(current) === sorted(stock)),
        '请从当前可用库存选择',
      )
      check(
        !lines.value.some(
          (line) => line.stockId === stock.stockId && line.sourceFlowId === sourceFlowId.value,
        ),
        '该库存已在本次明细中',
      )
      Object.assign(row, {
        stockId: stock.stockId,
        siteCode: stock.siteCode || '',
        containerCode: stock.containerCode || '',
        batch: stock.batch || '',
        barcode: stock.barcode || '',
      })
      // 同一实物分属不同退回记录时，也必须合计保护选取时的可用量。
      const available = decimal(stock.available, false)
      const previous = lines.value.some((line) => line.stockId === stock.stockId)
        ? stockLimits.get(stock.stockId)
        : undefined
      stockLimits.set(
        stock.stockId,
        previous === undefined || available < previous ? available : previous,
      )
    }
    if (action.value === 'RETURN' && inbound.value) {
      row.batch = selectedSource.value?.identity.matBatch || ''
      row.barcode = selectedSource.value?.identity.matBarcode || ''
    }
    lines.value.push(row)
  }
  function buildInput(): Input {
    check(detail.value && session.context.value && action.value !== ' ', '请选择申请及办理动作')
    const input = actionInput(
      detail.value,
      action.value,
      session.context.value,
      lines.value,
      reason.value,
      evidence.value,
      closeLines.value,
      mobile,
    )
    const byStock = new Map<string, bigint>()
    for (const row of input.lines) {
      if (row.stockId === null) {
        continue
      }
      const limit = stockLimits.get(row.stockId)
      check(limit !== undefined, '库存选择已失效，请重新选取实物')
      const requested = (byStock.get(row.stockId) ?? BigInt('0')) + decimal(row.quantity, false)
      check(requested <= limit, '所选库存本次合计超过可用量，请减少数量或重新选库')
      byStock.set(row.stockId, requested)
    }
    return input
  }
  function buildCreate(): Input {
    check(session.context.value, '请先核实当前仓库')
    return createInput(plan.value, session.context.value)
  }
  async function perform(input: Input, confirm: () => Promise<boolean>) {
    if (locked.value) return false
    const epoch = session.capture()
    const snapshot = sorted(input)
    confirming.value = true
    try {
      if (!(await confirm()) || !session.current(epoch)) return false
      check(snapshot === sorted(input), '办理内容已变化，请重新核对')
      await session.perform(clone(input), input.action)
      if (!session.current(epoch) || !session.last.value) return false
      const orderId = session.last.value.orderId
      resetAction()
      await loadList()
      // 刷新期间也可能切换范围，旧办理不能借新会话读取原单或打开新范围详情。
      if (!session.current(epoch)) return false
      if (orderId)
        await read<View>(
          'detail',
          (guard) => api.detail(warehouse(), orderId, guard),
          (value) => {
            detail.value = validateView(value, session.context.value!)
          },
        )
      return session.current(epoch)
    } finally {
      if (session.current(epoch)) {
        confirming.value = false
      }
    }
  }
  async function savePolicy(confirm: () => Promise<boolean>) {
    check(
      !mobile &&
        api.savePolicy &&
        policy.value &&
        session.context.value?.permissions.includes('config'),
      '当前入口无规则维护权限',
    )
    if (locked.value) return false
    const epoch = session.capture()
    const snapshot = clone(policy.value)
    const key = sorted(snapshot)
    validatePolicy(snapshot, session.context.value!)
    policySaving.value = true
    try {
      if (!(await confirm()) || !session.current(epoch)) return false
      check(key === sorted(policy.value), '规则已变化，请重新核对')
      const response = await api.savePolicy(
        { warehouseCode: warehouse(), expectedVersion: snapshot.version, rules: snapshot.rules },
        session.guard(epoch),
      )
      if (!session.current(epoch)) return false
      const value = validatePolicy(body<Policy>(response), session.context.value!)
      check(
        BigInt(value.version) === BigInt(snapshot.version) + BigInt('1') &&
          sorted(value.rules) === sorted(snapshot.rules),
        '保存结果尚未确认，请刷新规则核对',
      )
      policy.value = value
      session.context.value!.policy = clone(value)
      return true
    } catch (problem) {
      if (session.current(epoch)) error.value = errorText(problem, '规则保存未确认，请刷新后核对')
      return false
    } finally {
      if (session.current(epoch)) {
        policySaving.value = false
      }
    }
  }
  async function resolve(mode: 'query' | 'retry' | 'end', endReason = '') {
    const epoch = session.capture()
    const requestId = session.pending.value?.command.requestId
    if (!requestId || confirming.value || policySaving.value) {
      return false
    }
    // 与正常成功相同，原号恢复到结果回读完成期间保持办理锁。
    confirming.value = true
    try {
      await session.resolve(mode, endReason)
      if (!session.current(epoch)) {
        return false
      }
      const receipt = session.last.value
      if (receipt && receipt.requestId === requestId && !session.pending.value) {
        // 原号已确认后清空旧办理行，避免恢复成功后把同一实物再次按新号提交。
        resetAction()
        if (receipt.action === 'CREATE') {
          resetCreate()
        }
        await loadList()
        if (!session.current(epoch)) {
          return false
        }
        const orderId = receipt.orderId
        if (orderId)
          await read<View>(
            'detail',
            (guard) => api.detail(warehouse(), orderId, guard),
            (value) => {
              detail.value = validateView(value, session.context.value!)
            },
          )
        return session.current(epoch)
      }
      return false
    } finally {
      if (session.current(epoch)) {
        confirming.value = false
      }
    }
  }
  function lineName(value: string) {
    const row = detail.value?.balances.find((row) => row.lineId === value)
    return row ? `${row.materialName} / ${row.materialCode}` : '原计划物料'
  }
  return {
    records,
    total,
    page,
    kind,
    state,
    reference,
    detail,
    plan,
    policy,
    error,
    reads,
    confirming,
    policySaving,
    action,
    reason,
    evidence,
    lines,
    closeLines,
    materials,
    materialKeyword,
    materialPage,
    materialTotal,
    sites,
    stocks,
    stockCursor,
    stocksLoaded,
    stockBarcode,
    lineId,
    sourceFlowId,
    locked,
    enabledKinds,
    createRule,
    canCreate,
    availableActions,
    physical,
    sourceOptions,
    selectedSource,
    inbound,
    selectableLines,
    prepare,
    loadList,
    loadPage,
    openDetail,
    resetAction,
    resetCreate,
    loadMaterials,
    loadMaterialPage,
    addMaterial,
    chooseAction,
    loadSites,
    selectSource,
    captureSelection,
    loadStocks,
    addExecution,
    buildInput,
    buildCreate,
    perform,
    savePolicy,
    resolve,
    lineName,
  }
}
