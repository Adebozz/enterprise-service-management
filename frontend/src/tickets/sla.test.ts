import { describe, expect, it } from 'vitest'
import { formatMinutes } from './sla'

describe('SLA durations', () => {
  it('reads like people say targets', () => {
    expect(formatMinutes(15)).toBe('15 min')
    expect(formatMinutes(60)).toBe('1 h')
    expect(formatMinutes(90)).toBe('1 h 30 min')
    expect(formatMinutes(1440)).toBe('1 day')
    expect(formatMinutes(4320)).toBe('3 days')
    expect(formatMinutes(14400)).toBe('10 days')
  })

  it('shows overdue time without a sign and rounds to whole minutes', () => {
    expect(formatMinutes(-125)).toBe('2 h 5 min')
    expect(formatMinutes(0.4)).toBe('0 min')
  })
})
