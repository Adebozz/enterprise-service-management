import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { myProfileQuery } from '@/api/queries'
import { useCurrentUser } from '@/auth/AuthContext'
import { ROLE_LABEL } from '@/auth/roles'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

export function HomePage() {
  const user = useCurrentUser()
  const profile = useQuery(myProfileQuery)

  return (
    <section className="grid gap-6">
      <div>
        <h1 className="text-2xl font-semibold">Welcome, {user.displayName}</h1>
        <p className="mt-1 text-muted-foreground">
          Signed in as {user.email} <Badge variant="secondary">{ROLE_LABEL[user.role]}</Badge>
        </p>
      </div>

      <nav aria-label="Quick actions" className="grid gap-4 sm:grid-cols-3">
        {[
          { to: '/tickets/new/incident', title: 'Report a problem', text: 'Something is broken or not working as it should.' },
          { to: '/tickets/new/request', title: 'Request something', text: 'Equipment, access or software you need.' },
          { to: '/tickets', title: 'My tickets', text: 'Follow up on what you’ve raised.' },
        ].map((action) => (
          <Link key={action.to} to={action.to} className="rounded-xl border p-4 transition-colors hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none">
            <span className="font-medium">{action.title}</span>
            <span className="mt-1 block text-sm text-muted-foreground">{action.text}</span>
          </Link>
        ))}
      </nav>

      <Card className="max-w-xl">
        <CardHeader>
          <CardTitle>
            <h2>Your teams</h2>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {profile.isPending && (
            <div className="grid gap-2" aria-busy="true" aria-label="Loading your teams">
              <Skeleton className="h-5 w-48" />
              <Skeleton className="h-5 w-40" />
            </div>
          )}
          {profile.isError && (
            <Alert variant="destructive">
              <AlertDescription>Your teams couldn’t be loaded. Refresh the page to try again.</AlertDescription>
            </Alert>
          )}
          {profile.isSuccess &&
            (profile.data.teams.length === 0 ? (
              <p className="text-muted-foreground">You aren’t a member of any support team.</p>
            ) : (
              <ul className="grid gap-1">
                {profile.data.teams.map((team) => (
                  <li key={team.id}>{team.name}</li>
                ))}
              </ul>
            ))}
        </CardContent>
      </Card>
    </section>
  )
}
