export type Role = 'CENTRE' | 'KITCHEN' | 'OPS'
export type MealSlot = 'BREAKFAST' | 'LUNCH'
export type Status = 'REQUESTED' | 'LOCKED' | 'DISPATCHED' | 'RECEIVED' | 'CLOSED'

export interface Me {
  authenticated: boolean
  id?: number
  name?: string
  role?: Role
  centreId?: number
  centreName?: string
  vendorId?: number
  vendorName?: string
}

export interface DemoState {
  nowIndia: string
  today: string
  nextDay: string
  nextDayCutoffIndia: string
  anchorDay: string
  serviceDay: string
  password: string
  users: { role: Role; label: string; phone: string }[]
}

// ---------- centre ----------

export interface CentreOrderLine {
  id: number
  serviceDate: string
  mealSlot: MealSlot
  status: Status
  suggestedQty: number
  suggestionBasis: string
  requestedQty: number
  lockedQty?: number
  receivedQty?: number
  cutoffAt: string
  confirmBy: string
  closedReason?: string
  present?: number
  attendanceSource?: 'FRAT' | 'MANUAL'
  hasFeedback: boolean
}

export interface CentreHome {
  centre: { id: number; name: string; type: string; district: string; enrolled: number; routeName: string; vendorName: string }
  now: string
  today: string
  nextDay: string
  orders: { order: CentreOrderLine; canEdit: boolean; canReceive: boolean }[]
}

// ---------- kitchen ----------

export interface Stop {
  stop: number
  orderId: number
  centreId: number
  centreName: string
  workerName?: string
  workerPhone?: string
  status: Status
  requestedQty: number
  lockedQty?: number
  receivedQty?: number
  closedReason?: string
  confirmBy: string
  receivedAt?: string
}

export interface RouteBatch {
  routeId: number
  routeName: string
  mealSlot: MealSlot
  mealsToCook: number
  open: number
  locked: number
  dispatched: number
  received: number
  closed: number
  canDispatch: boolean
  dispatchBy: string
  dispatchedAt?: string
  vanStatus: 'WAITING' | 'DUE' | 'ON_TIME' | 'LATE'
  minutesLate: number
  stops: Stop[]
}

export interface PrepList {
  serviceDate: string
  now: string
  cutoffAt: string
  finalCounts: boolean
  totalMeals: number
  breakfastMeals: number
  lunchMeals: number
  vansLeft: number
  vansTotal: number
  vansLate: number
  routes: RouteBatch[]
}

export interface Change {
  eventId: number
  orderId: number
  centreName: string
  routeName: string
  mealSlot: MealSlot
  kind: 'COUNT_CHANGED' | 'CLOSED' | 'REOPENED' | 'OPS_OVERRIDE'
  beforeQty?: number
  afterQty?: number
  reason?: string
  byWhom: string
  at: string
}

export interface Money {
  serviceDate: string
  ratePaise: number
  mealsCooked: number
  mealsConfirmed: number
  mealsPending: number
  mealsNotPayable: number
  expectedPaise: number
  confirmedPaise: number
  pendingPaise: number
  pending: { orderId: number; centreName: string; routeName: string; mealSlot: MealSlot; status: Status; lockedQty: number; workerPhone?: string }[]
}

export interface Reviews {
  days: { serviceDate: string; reviews: number; onTimePercent: number; qualityOkPercent: number; quantityOkPercent: number; notes: number }[]
  notes: {
    feedbackId: number; serviceDate: string; mealSlot: MealSlot; centreName: string; workerName?: string
    quantityOk: boolean; qualityOk: boolean; onTime: boolean; note: string; tags?: string; tagStatus: string; createdAt: string
  }[]
}

export interface InvoiceDraft {
  serviceDate: string
  existingInvoiceId?: number
  existingStatus?: string
  lines: { orderId: number; centreName: string; routeName: string; mealSlot: MealSlot; status: Status; lockedQty?: number; receivedQty?: number }[]
}

// ---------- ops ----------

export interface Dashboard {
  date: string
  now: string
  summary: {
    orders: number; open: number; locked: number; dispatched: number; received: number; closed: number
    closedAfterLock: number; mealsPlanned: number; mealsReceived: number; unconfirmed: number; notDispatched: number
    receivedWithoutHeadcount: number
  }
  balance: {
    childrenPresent: number; mealsWithAttendance: number; wasteMeals: number; shortMeals: number
    shortages: number; wastePercent?: number; plannedWasteFromClosures: number
  }
  unconfirmed: {
    orderId: number; centreName: string; district: string; routeName: string; vendorName: string
    mealSlot: MealSlot; stage: 'NOT_DISPATCHED' | 'NOT_CONFIRMED'; lockedQty: number; confirmBy: string
  }[]
  shortages: { orderId: number; centreName: string; routeName: string; vendorName: string; mealSlot: MealSlot; receivedQty: number; present: number; gap: number }[]
  topWaste: { orderId: number; centreName: string; routeName: string; mealSlot: MealSlot; receivedQty: number; present: number; waste: number; wastePercent: number }[]
  trend: { serviceDate: string; mealsWithAttendance: number; wasteMeals: number; wastePercent?: number }[]
  nextDay: { serviceDate: string; cutoffAt: string; orders: number; closed: number; mealsRequested: number; enrolledChildren: number }
  invoicesSubmitted: number
  invoicesApproved: number
  lateVans: { routeId: number; routeName: string; vendorName: string; mealSlot: MealSlot; dispatchBy: string; leftAt?: string; minutesLate: number }[]
}

export interface InvoiceSummary {
  id: number; vendorId: number; vendorName: string; serviceDate: string; status: 'SUBMITTED' | 'REJECTED' | 'APPROVED' | 'PAID'
  lines: number; billedMeals: number; payableMeals: number; flaggedLines: number; unconfirmedLines: number; payablePaise: number
  billedPaise: number; submittedAt: string; approvedAt?: string; paidAt?: string; paymentRef?: string; rejectReason?: string
}

export interface InvoiceLine {
  lineId: number; orderId: number; centreName: string; routeName: string; mealSlot: MealSlot; orderStatus: Status
  billedQty: number; lockedQty?: number; receivedQty?: number; payableQty: number; flag: string; ratePaise: number; amountPaise: number
  receivedBy?: string; receivedAt?: string; childrenPresent?: number
}

export interface InvoiceEvent {
  id: number; kind: 'SUBMITTED' | 'REJECTED' | 'RESUBMITTED' | 'APPROVED' | 'PAID'; actorLabel: string; note?: string; amountPaise?: number; at: string
}

export interface InvoiceDetail {
  invoice: InvoiceSummary
  lines: InvoiceLine[]
  events: InvoiceEvent[]
}

export interface OrderTimeline {
  order: {
    id: number; centreName: string; centreType: string; district: string; enrolled: number; routeName: string
    vendorName: string; serviceDate: string; mealSlot: MealSlot; status: Status; suggestedQty: number
    suggestionBasis: string; requestedQty: number; lockedQty?: number; receivedQty?: number; cutoffAt: string
    confirmBy: string; closedReason?: string; present?: number; attendanceSource?: string; quantityOk?: boolean
    qualityOk?: boolean; onTime?: boolean; feedbackNote?: string; feedbackTags?: string; tagStatus?: string
  }
  events: { id: number; fromStatus?: string; toStatus: string; qty?: number; actorLabel: string; reason?: string; at: string }[]
}

export interface VendorScore {
  vendorId: number; vendorName: string; ordersReceived: number; vansOnTimePercent?: number; feedbackCount: number
  onTimePercent?: number; qualityOkPercent?: number; quantityOkPercent?: number; shortages: number
  lateTags: number; qualityTags: number; hygieneTags: number; quantityTags: number; tasteTags: number; pendingTags: number
}
