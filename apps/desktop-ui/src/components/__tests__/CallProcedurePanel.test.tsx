import { act, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ProcedureInspectResult } from '@/services/api'
import { CallProcedurePanel, type CallProcedureTarget } from '../CallProcedurePanel'

const apiMocks = vi.hoisted(() => ({
  inspect: vi.fn(),
  execute: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  procedureApi: apiMocks,
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise
  })
  return { promise, resolve }
}

function inspectResult(name: string, comment: string): ProcedureInspectResult {
  return {
    name,
    type: 'PROCEDURE',
    database: 'test_db',
    definer: null,
    comment,
    params: [],
    ddl: null,
  }
}

const target = (name: string): CallProcedureTarget => ({
  connectionId: 'mysql-1',
  database: 'test_db',
  name,
  type: 'PROCEDURE',
})

describe('CallProcedurePanel', () => {
  it('resets by target identity and ignores the previous target response', async () => {
    const firstRequest = deferred<ProcedureInspectResult>()
    apiMocks.inspect
      .mockReturnValueOnce(firstRequest.promise)
      .mockResolvedValueOnce(inspectResult('second_proc', '第二个过程'))

    const { rerender } = render(
      <CallProcedurePanel target={target('first_proc')} onClose={vi.fn()} />,
    )

    rerender(<CallProcedurePanel target={target('second_proc')} onClose={vi.fn()} />)
    expect(await screen.findByText('第二个过程')).toBeInTheDocument()

    await act(async () => {
      firstRequest.resolve(inspectResult('first_proc', '第一个过程'))
      await firstRequest.promise
    })

    expect(screen.queryByText('第一个过程')).not.toBeInTheDocument()
    expect(apiMocks.inspect).toHaveBeenCalledTimes(2)
  })
})
