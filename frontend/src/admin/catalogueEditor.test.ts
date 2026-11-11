import { describe, expect, it } from 'vitest'
import { aCatalogueItem } from '@/test/catalogue'
import { draftPath, emptyQuestion, keyFor, toDraft, toFields } from './catalogueEditor'

describe('catalogue editor', () => {
  it('makes camelCase keys from labels, unique and starting with a letter', () => {
    expect(keyFor('Asset tag (if known)', new Set())).toBe('assetTagIfKnown')
    expect(keyFor('Which model?', new Set(['whichModel']))).toBe('whichModel2')
    expect(keyFor('2nd monitor', new Set())).toBe('q2ndMonitor')
    expect(keyFor('???', new Set())).toBe('q')
    expect(keyFor('a'.repeat(60), new Set())).toHaveLength(36)
  })

  it('round-trips an item and keeps existing keys when questions are relabelled', () => {
    const draft = toDraft(aCatalogueItem())
    draft.questions = draft.questions.map((q, i) => (i === 0 ? { ...q, label: 'Laptop model' } : q))
    draft.questions.push({ ...emptyQuestion(), label: 'Laptop model', type: 'SELECT', optionsText: ' A \n\n B ' })

    const fields = toFields(draft.questions)

    expect(fields.map((f) => f.key)).toEqual(['model', 'neededBy', 'reason', 'laptopModel'])
    expect(fields[3]?.options).toEqual(['A', 'B'])
    expect(fields[1]?.options).toEqual([])
    expect(fields[2]?.helpText).toBe('A sentence is enough.')
    expect(fields[1]?.helpText).toBeNull()
  })

  it('places server errors next to the right input', () => {
    expect(draftPath('fields[2].options')).toBe('questions.2.optionsText')
    expect(draftPath('fields[0].key')).toBe('questions.0.label')
    expect(draftPath('name')).toBe('name')
    expect(draftPath('fields')).toBeNull()
  })
})
