import { Link } from 'react-router'

const AREAS = [
  { to: '/admin/people', title: 'People', text: 'Add people, change their role, or deactivate an account.' },
  { to: '/admin/teams', title: 'Teams', text: 'Create support teams and choose who belongs to each.' },
  { to: '/admin/categories', title: 'Categories', text: 'What tickets are about, and which team each kind is routed to.' },
  { to: '/admin/catalogue', title: 'Service catalogue', text: 'What people can order, and the questions each item asks.' },
  { to: '/admin/sla', title: 'SLA targets', text: 'Response and resolution targets per ticket type and priority.' },
]

/** One place for everything an administrator maintains. */
export function AdminHomePage() {
  return (
    <section className="grid gap-5">
      <div className="grid gap-1">
        <h1 className="text-2xl font-semibold">Administration</h1>
        <p className="text-muted-foreground">Set up the service desk: who works in it, how tickets are routed, and what is promised.</p>
      </div>
      <nav aria-label="Administration" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {AREAS.map((area) => (
          <Link
            key={area.to}
            to={area.to}
            className="rounded-xl border p-4 transition-colors hover:bg-muted focus-visible:ring-[3px] focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            <span className="font-medium">{area.title}</span>
            <span className="mt-1 block text-sm text-muted-foreground">{area.text}</span>
          </Link>
        ))}
      </nav>
    </section>
  )
}

/** "← Administration" at the top of each admin page. */
export function AdminBackLink() {
  return (
    <Link to="/admin" className="text-sm text-muted-foreground underline-offset-4 hover:underline">
      ← Administration
    </Link>
  )
}
