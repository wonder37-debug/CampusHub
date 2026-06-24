const DRAFT_KEY = 'campushub.demand.draft'

export interface DemandDraft {
  title: string
  description: string
  category: string
  campusZone: string
  location: string
  startDateTime: string
  endDateTime: string
  reward: string
  tags: string
  images: string[]
  anonymous: boolean
}

export function loadDemandDraft(): DemandDraft | null {
  try {
    const raw = localStorage.getItem(DRAFT_KEY)
    if (!raw) return null
    return JSON.parse(raw) as DemandDraft
  } catch {
    return null
  }
}

export function saveDemandDraft(draft: DemandDraft): void {
  localStorage.setItem(DRAFT_KEY, JSON.stringify(draft))
}

export function clearDemandDraft(): void {
  localStorage.removeItem(DRAFT_KEY)
}
