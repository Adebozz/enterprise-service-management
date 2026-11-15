import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '@/api/client'
import { unwrap } from '@/api/errors'
import { adminUsersQuery, queryKeys } from '@/api/queries'
import type { AdminUserSearch, CreateUser, User } from '@/api/types'
import { ROLE_LABEL, type Role } from '@/auth/roles'
import { NativeSelect } from '@/components/NativeSelect'
import { Pagination } from '@/components/Pagination'
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

const ROLES = Object.keys(ROLE_LABEL) as Role[]

/** Admins find people, add accounts, and change someone's name, role or whether they can sign in. */
export function PeoplePage() {
  const [search, setSearch] = useState<AdminUserSearch>({ page: 0, size: 20 })
  const [text, setText] = useState('')
  const [editing, setEditing] = useState<User | null>(null)
  const [adding, setAdding] = useState(false)
  const users = useQuery(adminUsersQuery(search))

  return (
    <section className="grid gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <AdminBackLink />
          <h1 className="text-2xl font-semibold">People</h1>
          <p className="text-muted-foreground">Everyone who can sign in. Deactivated people keep their history but can’t sign in.</p>
        </div>
        <Button onClick={() => setAdding(true)}>Add person</Button>
      </div>

      <form
        role="search"
        className="flex flex-wrap items-end gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          setSearch({ ...search, q: text.trim() || undefined, page: 0 })
        }}
      >
        <div className="grid gap-1">
          <Label htmlFor="people-q">Name or email</Label>
          <Input id="people-q" value={text} onChange={(event) => setText(event.target.value)} className="w-64" />
        </div>
        <div className="grid gap-1">
          <Label htmlFor="people-role">Role</Label>
          <NativeSelect
            id="people-role"
            value={search.role ?? ''}
            onChange={(event) => setSearch({ ...search, role: (event.target.value || undefined) as Role | undefined, page: 0 })}
            className="w-40"
          >
            <option value="">Any role</option>
            {ROLES.map((role) => (
              <option key={role} value={role}>
                {ROLE_LABEL[role]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="grid gap-1">
          <Label htmlFor="people-status">Status</Label>
          <NativeSelect
            id="people-status"
            value={search.active === undefined ? '' : String(search.active)}
            onChange={(event) => setSearch({ ...search, active: event.target.value === '' ? undefined : event.target.value === 'true', page: 0 })}
            className="w-36"
          >
            <option value="">Anyone</option>
            <option value="true">Active</option>
            <option value="false">Deactivated</option>
          </NativeSelect>
        </div>
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>

      {users.isPending && <Skeleton className="h-48 w-full" />}
      {users.isError && <QueryError error={users.error} what="The people list" />}
      {users.isSuccess &&
        (users.data.content.length === 0 ? (
          <p className="rounded-md border border-dashed p-8 text-center text-muted-foreground">Nobody matches.</p>
        ) : (
          <>
            <div className="overflow-x-auto rounded-md border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Email</TableHead>
                    <TableHead>Role</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>
                      <span className="sr-only">Edit</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {users.data.content.map((user) => (
                    <TableRow key={user.id}>
                      <TableCell className="font-medium">{user.displayName}</TableCell>
                      <TableCell>{user.email}</TableCell>
                      <TableCell>{ROLE_LABEL[user.role]}</TableCell>
                      <TableCell>{user.active ? <Badge variant="secondary">Active</Badge> : <Badge variant="outline">Deactivated</Badge>}</TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="outline" onClick={() => setEditing(user)} aria-label={`Edit ${user.displayName}`}>
                          Edit
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            <Pagination page={users.data.page} totalPages={users.data.totalPages} onChange={(page) => setSearch({ ...search, page })} />
          </>
        ))}
      {editing && <EditPersonDialog user={editing} onClose={() => setEditing(null)} />}
      {adding && <AddPersonDialog onClose={() => setAdding(false)} />}
    </section>
  )
}

function EditPersonDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [name, setName] = useState(user.displayName)
  const [role, setRole] = useState<Role>(user.role)
  const [active, setActive] = useState(user.active)
  const [failure, setFailure] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.PATCH('/api/admin/users/{id}', {
          params: { path: { id: user.id } },
          body: { displayName: name.trim() === user.displayName ? undefined : name.trim(), role, active, version: user.version },
        }),
      ),
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      await save.mutateAsync()
      await queryClient.invalidateQueries({ queryKey: queryKeys.allAdminUsers })
      onClose()
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The changes'))
      await queryClient.invalidateQueries({ queryKey: queryKeys.allAdminUsers })
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={(event) => void onSubmit(event)} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Edit {user.displayName}</DialogTitle>
            <DialogDescription>{user.email}</DialogDescription>
          </DialogHeader>
          {failure && (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{failure}</AlertDescription>
            </Alert>
          )}
          <div className="grid gap-1">
            <Label htmlFor="edit-name">Name</Label>
            <Input id="edit-name" required maxLength={120} value={name} onChange={(event) => setName(event.target.value)} />
          </div>
          <div className="grid gap-1">
            <Label htmlFor="edit-role">Role</Label>
            <NativeSelect id="edit-role" value={role} onChange={(event) => setRole(event.target.value as Role)}>
              {ROLES.map((option) => (
                <option key={option} value={option}>
                  {ROLE_LABEL[option]}
                </option>
              ))}
            </NativeSelect>
            {role === 'REQUESTER' && user.role !== 'REQUESTER' && (
              <p className="text-xs text-muted-foreground">Requesters can’t be in support teams, so they will leave their teams.</p>
            )}
          </div>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={active} onChange={(event) => setActive(event.target.checked)} />
            Can sign in
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

function AddPersonDialog({ onClose }: { onClose: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<CreateUser>({ email: '', displayName: '', role: 'REQUESTER', initialPassword: '' })
  const [failure, setFailure] = useState<string | null>(null)
  const create = useMutation({
    mutationFn: async () => unwrap(await api.POST('/api/admin/users', { body: { ...form, email: form.email.trim(), displayName: form.displayName.trim() } })),
  })

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setFailure(null)
    try {
      await create.mutateAsync()
      await queryClient.invalidateQueries({ queryKey: queryKeys.allAdminUsers })
      onClose()
    } catch (error) {
      setFailure(adminErrorMessage(error, 'The new account'))
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form onSubmit={(event) => void onSubmit(event)} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Add person</DialogTitle>
            <DialogDescription>Give them the initial password yourself; they can change it after signing in.</DialogDescription>
          </DialogHeader>
          {failure && (
            <Alert variant="destructive" role="alert">
              <AlertDescription>{failure}</AlertDescription>
            </Alert>
          )}
          <div className="grid gap-1">
            <Label htmlFor="new-email">Email</Label>
            <Input
              id="new-email"
              type="email"
              required
              maxLength={254}
              value={form.email}
              onChange={(event) => setForm({ ...form, email: event.target.value })}
            />
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-name">Name</Label>
            <Input
              id="new-name"
              required
              maxLength={120}
              value={form.displayName}
              onChange={(event) => setForm({ ...form, displayName: event.target.value })}
            />
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-role">Role</Label>
            <NativeSelect id="new-role" value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value as Role })}>
              {ROLES.map((option) => (
                <option key={option} value={option}>
                  {ROLE_LABEL[option]}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="grid gap-1">
            <Label htmlFor="new-password">Initial password</Label>
            <Input
              id="new-password"
              type="password"
              autoComplete="new-password"
              required
              minLength={12}
              maxLength={72}
              aria-describedby="new-password-hint"
              value={form.initialPassword}
              onChange={(event) => setForm({ ...form, initialPassword: event.target.value })}
            />
            <p id="new-password-hint" className="text-xs text-muted-foreground">
              At least 12 characters.
            </p>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Adding…' : 'Add person'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
