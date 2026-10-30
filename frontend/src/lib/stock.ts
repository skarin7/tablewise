export const ingStatus = (i: any) => (+i.currentStock <= 0 ? 'out' : +i.currentStock <= +i.reorderLevel ? 'low' : 'ok')
/** Dish availability comes from v_menu_availability: off, out, low, ok or untracked (README 7.3). */
export const dishState = (m: any): string => m.stockState ?? (m.available ? 'ok' : 'off')
