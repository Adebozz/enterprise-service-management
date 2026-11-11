import type { CatalogueItem } from '@/api/types'

export function aCatalogueItem(overrides: Partial<CatalogueItem> = {}): CatalogueItem {
  return {
    id: 'item-laptop',
    name: 'New laptop',
    summary: 'A standard or developer laptop.',
    description: 'Delivered within five working days.',
    category: { id: 'cat-hardware', name: 'Hardware' },
    subcategory: { id: 'cat-laptop', name: 'Laptop' },
    fields: [
      { key: 'model', label: 'Which model?', type: 'SELECT', required: true, options: ['Standard', 'Developer'], helpText: null },
      { key: 'neededBy', label: 'Needed by', type: 'DATE', required: false, options: [], helpText: null },
      { key: 'reason', label: 'Why do you need it?', type: 'LONG_TEXT', required: true, options: [], helpText: 'A sentence is enough.' },
    ],
    active: true,
    version: 0,
    ...overrides,
  }
}
