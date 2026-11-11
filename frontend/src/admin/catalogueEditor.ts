import type { CatalogueItem, FieldType, FormField } from '@/api/types'

/** A question as the editor holds it: options as one line each, the key hidden from admins. */
export interface QuestionDraft {
  /** Empty for a question added in this editing session; its key is made from the label on save. */
  key: string
  label: string
  type: FieldType
  required: boolean
  optionsText: string
  helpText: string
}

export interface ItemDraft {
  name: string
  summary: string
  description: string
  categoryId: string
  subcategoryId: string
  active: boolean
  approvalRequired: boolean
  questions: QuestionDraft[]
}

export const FIELD_TYPE_LABEL: Record<FieldType, string> = {
  TEXT: 'Short answer',
  LONG_TEXT: 'Paragraph',
  SELECT: 'Choice from a list',
  DATE: 'Date',
}

export const emptyQuestion = (): QuestionDraft => ({ key: '', label: '', type: 'TEXT', required: false, optionsText: '', helpText: '' })

export function toDraft(item?: CatalogueItem): ItemDraft {
  return {
    name: item?.name ?? '',
    summary: item?.summary ?? '',
    description: item?.description ?? '',
    categoryId: item?.category.id ?? '',
    subcategoryId: item?.subcategory?.id ?? '',
    active: item?.active ?? true,
    approvalRequired: item?.approvalRequired ?? false,
    questions: (item?.fields ?? []).map((field) => ({
      key: field.key,
      label: field.label,
      type: field.type,
      required: field.required,
      optionsText: (field.options ?? []).join('\n'),
      helpText: field.helpText ?? '',
    })),
  }
}

/**
 * The questions as the API expects them. Existing questions keep their key even when relabelled,
 * so answers on old requests still line up; new ones get a key made from their label.
 */
export function toFields(questions: QuestionDraft[]): FormField[] {
  const taken = new Set(questions.map((q) => q.key).filter(Boolean))
  return questions.map((q) => {
    const key = q.key || keyFor(q.label, taken)
    taken.add(key)
    return {
      key,
      label: q.label.trim(),
      type: q.type,
      required: q.required,
      options: q.type === 'SELECT' ? q.optionsText.split('\n').map((o) => o.trim()).filter(Boolean) : [],
      helpText: q.helpText.trim() || null,
    }
  })
}

/** "Asset tag (if known)" -> "assetTagIfKnown": camelCase, starts with a letter, at most 40 characters, unique. */
export function keyFor(label: string, taken: ReadonlySet<string>): string {
  const words = label.match(/[A-Za-z0-9]+/g) ?? []
  let base = words.map((word, i) => (i === 0 ? word.toLowerCase() : word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())).join('')
  if (!/^[a-z]/.test(base)) base = `q${base}`
  base = base.slice(0, 36)
  let key = base
  for (let n = 2; taken.has(key); n++) key = `${base}${n}`
  return key
}

/** Where a server error about the item belongs in the editor: "fields[2].options" -> "questions.2.optionsText". */
export function draftPath(serverField: string): string | null {
  const question = /^fields\[(\d+)]\.(\w+)$/.exec(serverField)
  if (question) {
    const [, index, part] = question
    const target = part === 'options' ? 'optionsText' : part === 'key' ? 'label' : part
    return `questions.${index}.${target}`
  }
  return ['name', 'summary', 'description', 'categoryId', 'subcategoryId'].includes(serverField) ? serverField : null
}
