import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { adminCategoriesQuery, adminTeamsQuery, queryKeys } from '@/api/queries'
import type { Category, CategoryScope, CreateCategory } from '@/api/types'
import { NativeSelect } from '@/components/NativeSelect'
import { QueryError } from '@/components/QueryError'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { AdminBackLink } from './AdminHomePage'
import { adminErrorMessage } from './adminErrors'

const SCOPE_LABEL: Record<CategoryScope, string> = {
  INCIDENT: 'Incidents (and problems, changes)',
  SERVICE_REQUEST: 'Service requests',
  ANY: 'Any ticket',
}

/** Admins keep the category tree: what tickets are about and which team each kind goes to. */
export function CategoriesAdminPage() {
  const categories = useQuery(adminCategoriesQuery)
  const teams = useQuery(adminTeamsQuery)
  const [editing, setEditing] = useState<Category | null>(null)
  const [adding, setAdding] = useState(false)
  const teamName = (id: string | null | undefined) => (id ? (teams.data?.find((team) => team.id === id)?.name ?? '…') : null)

  return (
    <section className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <AdminBackLink />
          <h1 className="text-2xl font-semibold">Categories</h1>
          <p className="text-muted-foreground">
            Every ticket has a category, and the category decides which team receives it. A subcategory can route to its own team or use its parent’s.
          </p>
        </div>
        <Button onClick={() => setAdding(true)}>Add category</Button>
      </div>
      {(categories.isPending || teams.isPending) && <Skeleton className="h-48 w-full" />}
      {categories.isError && <QueryError error={categories.error} what="The categories" />}
      {categories.isSuccess && teams.isSuccess && (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Category</TableHead>
                <TableHead>Code</TableHead>
                <TableHead>Used for</TableHead>
                <TableHead>Routed to</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>
                  <span className="sr-only">Edit</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {categories.data
                .flatMap((parent) => [parent, ...parent.subcategories])
                .map((category) => {
                  const isSub = Boolean(category.parentId)
                  return (
                    <TableRow key={category.id}>
                      <TableCell className={isSub ? 'pl-8' : 'font-medium'}>
                        {isSub && <span aria-hidden="true">↳ </span>}
                        {category.name}
                      </TableCell>
                      <TableCell className="font-mono text-xs">{category.code}</TableCell>
                      <TableCell>{SCOPE_LABEL[category.appliesTo]}</TableCell>
                      <TableCell>{teamName(category.defaultTeamId) ?? <span className="text-muted-foreground">Parent’s team</span>}</TableCell>
                      <TableCell>{category.active ? <Badge variant="secondary">Active</Badge> : <Badge variant="outline">Retired</Badge>}</TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="outline" onClick={() => setEditing(category)} aria-label={`Edit ${category.name}`}>
                          Edit
                        </Button>
                      </TableCell>
                    </TableRow>
                  )
                })}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && teams.data && <EditCategoryDialog category={editing} teams={teams.data} onClose={() => setEditing(null)} />}
      {adding && categories.data && teams.data && <AddCategoryDialog parents={categories.data} teams={teams.data} onClose={() => setAdding(false)} />}
    </section>
  )
}

type TeamOption = { id: string; name: string; active: boolean }

function TeamSelect({
  id,
  value,
  onChange,
  teams,
  emptyLabel,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  teams: TeamOption[]
  emptyLabel: string
}) {
  return (
    <NativeSelect id={id} value={value} onChange={(event) => onChange(event.target.value)}>
      <option value="">{emptyLabel}</option>
      {teams
        .filter((team) => team.active || team.id === value)
        .map((team) => (
          <option key={team.id} value={team.id}>
            {team.name}
          </option>
        ))}
    </NativeSelect>
  )
}

function EditCategoryDialog({ category, teams, onClose }: { category: Category; teams: TeamOption[]; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(category.name)
  const [teamId, setTeamId] = useState(category.defaultTeamId ?? '')
  const [active, setActive] = useState(category.active)
  const [failure, setFailure] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.PATCH('/api/admin/categories/{id}', {
          params: { path: { id: category.id } },
          body: { name: name.trim(), defaultTeamId: teamId || undefined, active, version: category.version },
        }),
      ),
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      await save.mutateAsync()
      await queryClient.invalidateQueries({ queryKey: queryKeys.allCategories })
      onClose()
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The category'))
      await queryClient.invalidateQueries({ queryKey: queryKeys.adminCategories })
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={(event) => void onSubmit(event)} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Edit {category.name}</DialogTitle>
            <DialogDescription>
              Code {category.code} · {SCOPE_LABEL[category.appliesTo]}. The code, parent and use are fixed once created.
            </DialogDescription>
          </DialogHeader>
          {failure && (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{failure}</AlertDescription>
            </Alert>
          )}
          <div className="grid gap-1">
            <Label htmlFor="category-name">Name</Label>
            <Input id="category-name" required maxLength={100} value={name} onChange={(event) => setName(event.target.value)} />
          </div>
          <div className="grid gap-1">
            <Label htmlFor="category-team">Routed to</Label>
            <TeamSelect
              id="category-team"
              value={teamId}
              onChange={setTeamId}
              teams={teams}
              emptyLabel={category.parentId ? 'Parent’s team' : 'Choose a team…'}
            />
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} />
            Offered for new tickets
          </label>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function AddCategoryDialog({ parents, teams, onClose }: { parents: Category[]; teams: TeamOption[]; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<CreateCategory>({ code: '', name: '', appliesTo: 'INCIDENT' })
  const [failure, setFailure] = useState<string | null>(null)
  const parent = parents.find((candidate) => candidate.id === form.parentId)
  const create = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST('/api/admin/categories', {
          body: {
            ...form,
            code: form.code.trim().toUpperCase(),
            name: form.name.trim(),
            parentId: form.parentId || undefined,
            defaultTeamId: form.defaultTeamId || undefined,
          },
        }),
      ),
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      await create.mutateAsync()
      await queryClient.invalidateQueries({ queryKey: queryKeys.allCategories })
      onClose()
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The category'))
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={(event) => void onSubmit(event)} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Add category</DialogTitle>
            <DialogDescription>A top-level category must route to a team; a subcategory can use its parent’s.</DialogDescription>
          </DialogHeader>
          {failure && (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{failure}</AlertDescription>
            </Alert>
          )}
          <div className="grid gap-1">
            <Label htmlFor="new-category-parent">Inside</Label>
            <NativeSelect
              id="new-category-parent"
              value={form.parentId ?? ''}
              onChange={(event) => {
                const chosen = parents.find((candidate) => candidate.id === event.target.value)
                setForm({
                  ...form,
                  parentId: event.target.value || undefined,
                  appliesTo: chosen && chosen.appliesTo !== 'ANY' ? chosen.appliesTo : form.appliesTo,
                })
              }}
            >
              <option value="">Nothing (a top-level category)</option>
              {parents.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-category-name">Name</Label>
            <Input id="new-category-name" required maxLength={100} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} />
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-category-code">Code</Label>
            <Input
              id="new-category-code"
              required
              pattern="[A-Za-z][A-Za-z0-9_]{1,49}"
              aria-describedby="new-category-code-hint"
              className="font-mono"
              value={form.code}
              onChange={(event) => setForm({ ...form, code: event.target.value.toUpperCase() })}
            />
            <p id="new-category-code-hint" className="text-xs text-muted-foreground">
              Letters, digits and underscores, such as EMAIL_ACCESS. Fixed once created.
            </p>
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-category-scope">Used for</Label>
            <NativeSelect
              id="new-category-scope"
              value={form.appliesTo}
              disabled={Boolean(parent && parent.appliesTo !== 'ANY')}
              onChange={(event) => setForm({ ...form, appliesTo: event.target.value as CategoryScope })}
            >
              {(Object.keys(SCOPE_LABEL) as CategoryScope[]).map((scope) => (
                <option key={scope} value={scope}>
                  {SCOPE_LABEL[scope]}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-category-team">Routed to</Label>
            <TeamSelect
              id="new-category-team"
              value={form.defaultTeamId ?? ''}
              onChange={(value) => setForm({ ...form, defaultTeamId: value || undefined })}
              teams={teams}
              emptyLabel={form.parentId ? 'Parent’s team' : 'Choose a team…'}
            />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Adding…' : 'Add category'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
