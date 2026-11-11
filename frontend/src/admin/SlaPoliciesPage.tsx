import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { isApiError, unwrap } from '@/api/errors'
import { queryKeys, slaPoliciesQuery } from '@/api/queries'
import type { SlaPolicy, UpdateSlaPolicy } from '@/api/types'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { PriorityBadge } from '@/tickets/badges'
import { TYPE_LABEL } from '@/tickets/labels'
import { formatMinutes } from '@/tickets/sla'

/** Admins set the response and resolution targets for each ticket type and priority. */
export function SlaPoliciesPage() {
  const policies = useQuery(slaPoliciesQuery)

  return (
    <section className="grid gap-5">
      <div className="grid gap-1">
        <h1 className="text-2xl font-semibold">SLA targets</h1>
        <p className="text-muted-foreground">
          How long support has to first respond to, and to resolve, each kind of ticket. Times run around the clock and pause while
          waiting for the requester. Changes apply to tickets raised afterwards.
        </p>
      </div>
      {policies.isPending && <Skeleton className="h-64 w-full" />}
      {policies.isError && <QueryError error={policies.error} what="The SLA targets" />}
      {policies.isSuccess && (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Ticket type</TableHead>
                <TableHead>Priority</TableHead>
                <TableHead>First response (minutes)</TableHead>
                <TableHead>Resolution (minutes)</TableHead>
                <TableHead>
                  <span className="sr-only">Save</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {policies.data.map((policy) => (
                <PolicyRow key={`${policy.id}-${policy.version}`} policy={policy} />
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </section>
  )
}

function PolicyRow({ policy }: { policy: SlaPolicy }) {
  const queryClient = useQueryClient()
  const [response, setResponse] = useState(String(policy.responseMinutes))
  const [resolution, setResolution] = useState(String(policy.resolutionMinutes))
  const [message, setMessage] = useState<string | null>(null)
  const name = `${TYPE_LABEL[policy.ticketType]} ${policy.priority}`

  const save = useMutation({
    mutationFn: async (body: UpdateSlaPolicy) =>
      unwrap(await api.PUT('/api/admin/sla-policies/{id}', { params: { path: { id: policy.id } }, body })),
  })

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setMessage(null)
    try {
      await save.mutateAsync({ responseMinutes: Number(response), resolutionMinutes: Number(resolution), version: policy.version })
      await queryClient.invalidateQueries({ queryKey: queryKeys.slaPolicies })
    } catch (error) {
      if (isApiError(error) && error.code === 'CONCURRENT_MODIFICATION') {
        setMessage('Someone else changed these targets meanwhile. They have been reloaded; please try again.')
        await queryClient.invalidateQueries({ queryKey: queryKeys.slaPolicies })
      } else {
        setMessage(isApiError(error) ? error.message : 'The targets couldn’t be saved. Please try again.')
      }
    }
  }

  const unchanged = Number(response) === policy.responseMinutes && Number(resolution) === policy.resolutionMinutes
  const formId = `sla-${policy.id}`
  return (
    <TableRow>
      <TableCell>{TYPE_LABEL[policy.ticketType]}</TableCell>
      <TableCell>
        <PriorityBadge priority={policy.priority} />
      </TableCell>
      <TableCell>
        <MinutesInput form={formId} label={`${name} first response, in minutes`} value={response} onChange={setResponse} />
      </TableCell>
      <TableCell>
        <MinutesInput form={formId} label={`${name} resolution, in minutes`} value={resolution} onChange={setResolution} />
      </TableCell>
      <TableCell>
        <form id={formId} onSubmit={(event) => void onSubmit(event)}>
          <Button type="submit" size="sm" variant="outline" disabled={unchanged || save.isPending} aria-label={`Save ${name}`}>
            Save
          </Button>
        </form>
        {message && (
          <Alert variant="destructive" role="alert" className="mt-2">
            <AlertDescription>{message}</AlertDescription>
          </Alert>
        )}
      </TableCell>
    </TableRow>
  )
}

function MinutesInput(props: { form: string; label: string; value: string; onChange: (value: string) => void }) {
  const minutes = Number(props.value)
  return (
    <div className="grid gap-1">
      <Input
        form={props.form}
        type="number"
        min={1}
        max={525600}
        required
        aria-label={props.label}
        value={props.value}
        onChange={(event) => props.onChange(event.target.value)}
        className="w-28"
      />
      <span className="text-xs text-muted-foreground">{Number.isFinite(minutes) && minutes > 0 ? formatMinutes(minutes) : ' '}</span>
    </div>
  )
}
