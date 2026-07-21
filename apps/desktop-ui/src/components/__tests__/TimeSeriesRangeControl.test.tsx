import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { TimeSeriesRangeControl } from '../TimeSeriesRangeControl'

describe('TimeSeriesRangeControl', () => {
  it('shows the default last-hour preset without querying on selection changes', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const onQuery = vi.fn()
    render(
      <TimeSeriesRangeControl
        value={{ preset: '1h', applied: false }}
        onChange={onChange}
        onQuery={onQuery}
      />,
    )

    expect(screen.getByText('最近 1 小时')).toBeInTheDocument()
    await user.click(screen.getByLabelText('时间范围预设'))
    await user.click(screen.getByText('不限时间'))

    expect(onChange).toHaveBeenCalledWith({ preset: 'all', applied: false })
    expect(onQuery).not.toHaveBeenCalled()
  })

  it('validates custom input before querying', async () => {
    const user = userEvent.setup()
    const onQuery = vi.fn()
    render(
      <TimeSeriesRangeControl
        value={{ preset: 'custom', customStartLocal: '', customEndLocal: '' }}
        onChange={vi.fn()}
        onQuery={onQuery}
      />,
    )

    await user.click(screen.getByRole('button', { name: '执行时间范围查询' }))
    expect(screen.getByRole('alert')).toHaveTextContent('开始时间')
    expect(onQuery).not.toHaveBeenCalled()
  })

  it('submits only after an explicit query action', () => {
    const onQuery = vi.fn()
    render(
      <TimeSeriesRangeControl
        value={{
          preset: 'custom',
          customStartLocal: '2026-07-17T11:00:00',
          customEndLocal: '2026-07-17T12:00:00',
        }}
        onChange={vi.fn()}
        onQuery={onQuery}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: '执行时间范围查询' }))
    expect(onQuery).toHaveBeenCalledTimes(1)
  })
})
