type DemandLike = {
  interactionMode?: string | null
  category?: string | null
  status?: string | null
  publisherId?: string | null
}

type OrderLike = {
  status?: string | null
  serviceProviderId?: string | null
}

export type DemandDetailMode = 'direct-accept' | 'select-one' | 'select-many' | 'help'
export type OrderFlowKind = 'direct-accept' | 'selected-response'

export function getDemandDetailMode(demand: DemandLike): DemandDetailMode {
  switch (demand.interactionMode) {
    case 'SELECT_ONE': return 'select-one'
    case 'SELECT_MANY': return 'select-many'
    case 'HELP': return 'help'
    default: return 'direct-accept'
  }
}

export function getOrderFlowKind(interactionMode?: string | null): OrderFlowKind {
  return interactionMode === 'SELECT_ONE' ? 'selected-response' : 'direct-accept'
}

export function canRespondToDemand(demand: DemandLike, userId?: string, role?: string): boolean {
  return demand.status === 'PENDING'
    && Boolean(userId)
    && role !== 'ADMIN'
    && userId !== demand.publisherId
    && getDemandDetailMode(demand) !== 'direct-accept'
}

export function canSelectResponse(
  demand: DemandLike,
  userId: string | undefined,
  role: string | undefined,
  responseStatus: string
): boolean {
  return demand.status === 'PENDING'
    && getDemandDetailMode(demand) !== 'direct-accept'
    && getDemandDetailMode(demand) !== 'help'
    && Boolean(userId)
    && userId === demand.publisherId
    && role !== 'ADMIN'
    && responseStatus === 'PENDING'
}

export function canViewSelectedResponse(
  isLinkedToFormalOrder: boolean,
  userId: string | undefined,
  publisherId: string,
  role: string | undefined,
  authorId?: string
): boolean {
  return !isLinkedToFormalOrder
    || userId === publisherId
    || userId === authorId
    || role === 'ADMIN'
}

export function canCancelOrder(order: OrderLike, userId: string | undefined, role: string | undefined): boolean {
  return order.status === 'ACCEPTED'
    && Boolean(userId)
    && userId === order.serviceProviderId
    && role !== 'ADMIN'
}
